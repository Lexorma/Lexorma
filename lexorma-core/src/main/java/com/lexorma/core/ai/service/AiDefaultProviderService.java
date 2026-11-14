package com.lexorma.core.ai.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.dto.AiProviderDto.DefaultStatus;
import com.lexorma.core.ai.domain.entity.AiProvider;
import com.lexorma.core.ai.domain.entity.AiSetting;
import com.lexorma.core.ai.repository.AiProviderRepository;
import com.lexorma.core.ai.repository.AiSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시스템 기본 AI Provider (docs/ai-provider-ai3.md 13장) : 업무 AI 기능이 쓸 Provider 를 관리자가 명시적으로 하나 정한다 (없어도 된다).
 * <ul>
 *   <li>저장 : 설정 행 하나(TB_AI_STNG)의 Provider 참조. 지정·해제는 그 행을 배타로 잡고 바꾼다 — 동시 요청은 차례대로 적용되고 마지막 것이 남는다.
 *       Provider 삭제도 같은 행을 먼저 잡으므로 (AiProviderService.delete) 지정과 삭제가 엇갈리지 않는다</li>
 *   <li>지정 조건 : 있음 · 활성 · 기본 모델 있음 · 지원하는 유형. AI 서버 연결·SEC-1 허용 규칙은 보지 않는다 (호출할 때 확인한다)</li>
 *   <li>자동으로 지정·변경·해제하지 않는다 (첫 등록·연결 성공·비활성화·삭제 시도 모두). 기본 Provider 를 비활성화해도 지정은 남고 호출만 막힌다.
 *       다른 Provider 로 바꿔 부르지 않는다</li>
 *   <li>모델은 그 Provider 의 기본 모델을 쓴다 (따로 저장하지 않는다). 캐시 없음 — 바꾸면 다음 요청부터 적용된다</li>
 * </ul>
 * 유형에 따른 조건을 두지 않는다 : 모델 객체 생성은 유형별 어댑터(AI-3)가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class AiDefaultProviderService {

    private final AiSettingRepository settings;
    private final AiProviderRepository providers;
    private final AiProviderService providerService;

    /** 지금의 기본 Provider 와 쓸 수 있는지 (연결하지 않는다) */
    @Transactional(readOnly = true)
    public AiProviderDto.DefaultProvider get() {
        AiSetting setting = settings.findById(AiSetting.SYSTEM).orElse(null);
        AiProvider provider = setting == null || setting.getDefaultProviderId() == null ? null : providers.findById(setting.getDefaultProviderId()).orElse(null);
        BaseEntity.Audit audit = setting == null ? null : setting.audit();
        return new AiProviderDto.DefaultProvider(status(provider), provider == null ? null : AiProviderService.dto(provider),
                audit == null ? null : audit.updatedBy(), audit == null ? null : audit.updated());
    }

    /** 기본 Provider 지정·변경 (같은 Provider 를 다시 지정해도 된다) */
    @Transactional
    public AiProviderDto.DefaultProvider set(AiProviderDto.DefaultProviderRequest request) {
        Long id = request == null ? null : request.providerId();
        if (id == null) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "기본으로 지정할 Provider 를 선택해주세요.");
        AiSetting setting = lockSetting();
        AiProvider provider = providers.lockById(id).orElseThrow(() -> new CustomException(ErrorCode.AI_PROVIDER_NOT_FOUND));
        providerService.adapter(provider.getType());
        if (!Boolean.TRUE.equals(provider.getEnabled())) throw new CustomException(ErrorCode.AI_PROVIDER_DISABLED, "비활성화된 Provider 는 기본으로 지정할 수 없습니다.");
        if (provider.getDefaultModel() == null) {
            throw new CustomException(ErrorCode.AI_DEFAULT_PROVIDER_MODEL_MISSING, "기본 모델이 없는 Provider 는 기본으로 지정할 수 없습니다. Provider 의 기본 모델을 먼저 정하세요.");
        }
        setting.setDefaultProviderId(provider.getId());
        settings.saveAndFlush(setting);
        return get();
    }

    /** 기본 Provider 지정 해제 (미지정 상태가 된다) */
    @Transactional
    public AiProviderDto.DefaultProvider clear() {
        AiSetting setting = lockSetting();
        if (setting.getDefaultProviderId() != null) {
            setting.setDefaultProviderId(null);
            settings.saveAndFlush(setting);
        }
        return get();
    }

    /**
     * 업무 AI 기능이 쓸 기본 Provider : 미지정 {@link ErrorCode#AI_DEFAULT_PROVIDER_NOT_CONFIGURED}, 비활성 {@link ErrorCode#AI_PROVIDER_DISABLED},
     * 기본 모델 없음 {@link ErrorCode#AI_DEFAULT_PROVIDER_MODEL_MISSING}, 지원하지 않는 유형 {@link ErrorCode#AI_PROVIDER_UNSUPPORTED}. 연결하지 않는다
     */
    @Transactional(readOnly = true)
    public AiProviderDto.DefaultTarget resolve() {
        AiSetting setting = settings.findById(AiSetting.SYSTEM).orElse(null);
        AiProvider provider = setting == null || setting.getDefaultProviderId() == null ? null : providers.findById(setting.getDefaultProviderId()).orElse(null);
        switch (status(provider)) {
            case NOT_CONFIGURED -> throw new CustomException(ErrorCode.AI_DEFAULT_PROVIDER_NOT_CONFIGURED);
            case UNSUPPORTED -> throw new CustomException(ErrorCode.AI_PROVIDER_UNSUPPORTED);
            case DISABLED -> throw new CustomException(ErrorCode.AI_PROVIDER_DISABLED);
            case MODEL_MISSING -> throw new CustomException(ErrorCode.AI_DEFAULT_PROVIDER_MODEL_MISSING);
            case READY -> {
                // 아래에서 돌려준다
            }
        }
        return new AiProviderDto.DefaultTarget(provider.getId(), provider.getType(), provider.getBaseUrl(), provider.getDefaultModel(), true);
    }

    private DefaultStatus status(AiProvider provider) {
        if (provider == null) return DefaultStatus.NOT_CONFIGURED;
        if (!providerService.supports(provider.getType())) return DefaultStatus.UNSUPPORTED;
        if (!Boolean.TRUE.equals(provider.getEnabled())) return DefaultStatus.DISABLED;
        if (provider.getDefaultModel() == null) return DefaultStatus.MODEL_MISSING;
        return DefaultStatus.READY;
    }

    private AiSetting lockSetting() {
        return settings.lockSystem().orElseThrow(() -> new IllegalStateException("TB_AI_STNG 의 설정 행(AI_STNG_SN = 1)이 없습니다 (V9 마이그레이션)"));
    }
}
