package com.lexorma.core.ai.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.ai.AiProviderType;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.entity.AiProvider;
import com.lexorma.core.ai.provider.AiProviderAdapter;
import com.lexorma.core.ai.repository.AiProviderRepository;
import com.lexorma.core.network.NetworkAddresses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI Provider 설정 관리 (AI-3) : 시스템 관리자(ADMIN, 컨트롤러가 확인) 전용. 설정은 DB 에만 있고 환경 설정·자동 등록은 없다 (Provider 가 없어도 Lexorma 는 그대로 동작한다).
 * 저장만 하고 AI 서버에 연결하지 않는다 — 연결은 연결 테스트·모델 목록·호출 때만 ({@link AiProviderClients}).
 * 서버 검증 : 이름(필수·100자·중복 금지, 대소문자 무시), 유형(지원하는 것만), 주소(http·https, 호스트 필수, 계정 정보·쿼리·프래그먼트 금지, 255자),
 * 기본 모델 ID(유형의 형식), 설명 1000자.
 */
@Service
public class AiProviderService {

    private final AiProviderRepository providers;
    private final Map<AiProviderType, AiProviderAdapter> adapters = new EnumMap<>(AiProviderType.class);

    public AiProviderService(AiProviderRepository providers, List<AiProviderAdapter> adapters) {
        this.providers = providers;
        adapters.forEach(adapter -> this.adapters.put(adapter.type(), adapter));
    }

    @Transactional(readOnly = true)
    public List<AiProviderDto.Provider> list() {
        return providers.findAllByOrderByIdAsc().stream().map(AiProviderService::dto).toList();
    }

    @Transactional(readOnly = true)
    public AiProviderDto.Provider get(Long id) {
        return dto(find(id));
    }

    /** 지원하는 유형 (화면의 선택지) */
    public List<AiProviderDto.TypeInfo> types() {
        return Arrays.stream(AiProviderType.values()).filter(adapters::containsKey)
                .map(type -> new AiProviderDto.TypeInfo(type, adapters.get(type).defaultBaseUrl(), adapters.get(type).supportsModelList())).toList();
    }

    @Transactional
    public AiProviderDto.Provider create(AiProviderDto.ProviderRequest request) {
        AiProvider provider = new AiProvider();
        apply(provider, request, null);
        return dto(providers.saveAndFlush(provider));
    }

    @Transactional
    public AiProviderDto.Provider update(Long id, AiProviderDto.ProviderRequest request) {
        AiProvider provider = find(id);
        apply(provider, request, id);
        return dto(providers.saveAndFlush(provider));
    }

    @Transactional
    public void delete(Long id) {
        providers.delete(find(id));
    }

    AiProvider find(Long id) {
        if (id == null) throw new CustomException(ErrorCode.AI_PROVIDER_NOT_FOUND);
        return providers.findById(id).orElseThrow(() -> new CustomException(ErrorCode.AI_PROVIDER_NOT_FOUND));
    }

    AiProviderAdapter adapter(AiProviderType type) {
        AiProviderAdapter adapter = type == null ? null : adapters.get(type);
        if (adapter == null) throw new CustomException(ErrorCode.AI_PROVIDER_UNSUPPORTED);
        return adapter;
    }

    private void apply(AiProvider provider, AiProviderDto.ProviderRequest request, Long id) {
        if (request == null) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "요청이 비어 있습니다.");
        String name = trim(request.name());
        if (name == null) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "Provider 이름을 입력해주세요.");
        if (name.length() > 100) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "Provider 이름은 100자까지 입력할 수 있습니다.");
        boolean duplicated = id == null ? providers.existsByNameIgnoreCase(name) : providers.existsByNameIgnoreCaseAndIdNot(name, id);
        if (duplicated) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "같은 이름의 Provider 가 이미 있습니다.");

        AiProviderAdapter adapter = adapter(type(request.type()));
        String model = trim(request.defaultModel());
        if (model != null && !adapter.isValidModelId(model)) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "모델 ID 형식이 올바르지 않습니다.");
        String description = trim(request.description());
        if (description != null && description.length() > 1000) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "설명은 1000자까지 입력할 수 있습니다.");

        provider.setName(name);
        provider.setType(adapter.type());
        provider.setBaseUrl(baseUrl(request.baseUrl()));
        provider.setDefaultModel(model);
        provider.setEnabled(request.enabled() == null || request.enabled());
        provider.setDescription(description);
    }

    private static AiProviderType type(String value) {
        if (value == null || value.isBlank()) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "Provider 유형을 선택해주세요.");
        try {
            return AiProviderType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.AI_PROVIDER_UNSUPPORTED, "지원하지 않는 Provider 유형입니다: {0}", value.trim());
        }
    }

    /** 주소 형식만 본다. 연결 허용 여부는 연결할 때 SEC-1 이 판정한다 (여기서 이름을 풀거나 연결하지 않는다) */
    static String baseUrl(String value) {
        String text = trim(value);
        if (text == null) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "AI 서버 주소를 입력해주세요.");
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.length() > 255) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "AI 서버 주소는 255자까지 입력할 수 있습니다.");
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "AI 서버 주소 형식이 올바르지 않습니다.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "AI 서버 주소는 http:// 또는 https:// 로 시작해야 합니다.");
        if (uri.getRawUserInfo() != null) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "주소에 계정 정보를 넣을 수 없습니다.");
        if (uri.getHost() == null || !NetworkAddresses.isValidHost(NetworkAddresses.normalizeHost(uri.getHost()))) {
            throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "AI 서버 주소의 호스트가 올바르지 않습니다.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "AI 서버 주소에 쿼리나 # 을 넣을 수 없습니다.");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "포트는 1~65535 사이로 입력해주세요.");
        return scheme + text.substring(scheme.length());
    }

    private static String trim(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    static AiProviderDto.Provider dto(AiProvider provider) {
        var audit = provider.audit();
        return new AiProviderDto.Provider(provider.getId(), provider.getName(), provider.getType(), provider.getBaseUrl(), provider.getDefaultModel(),
                Boolean.TRUE.equals(provider.getEnabled()), provider.getDescription(), audit.createdBy(), audit.created(), audit.updatedBy(), audit.updated());
    }
}
