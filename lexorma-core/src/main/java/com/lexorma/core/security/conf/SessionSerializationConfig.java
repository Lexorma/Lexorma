package com.lexorma.core.security.conf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.Deserializer;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamClass;

/**
 * DB 에 저장된 세션(Spring Session JDBC)의 역직렬화.
 * <p>
 * 프로젝트 이름을 바꾸면서 기본 패키지가 {@code com.koboolean.metamora} → {@code com.lexorma} 로 바뀌었다.
 * 세션에는 principal(AccountDto) 등이 클래스 이름과 함께 직렬화되어 있어, 이름을 바꾸기 전에 만든 세션은 그대로는 읽을 수 없고
 * 그 쿠키를 가진 사용자의 모든 요청이 500 이 된다. 예전 패키지 이름을 지금 클래스로 읽어 로그인 상태를 유지하고,
 * 그래도 읽지 못하는 값은 없는 값으로 본다 (로그인이 풀릴 뿐 오류가 나지 않는다).
 */
@Configuration
public class SessionSerializationConfig implements BeanClassLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(SessionSerializationConfig.class);
    /** 이름을 바꾸기 전의 기본 패키지 (저장된 세션에만 남아 있다) */
    private static final String LEGACY_PACKAGE = "com.koboolean.metamora.";
    private static final String PACKAGE = "com.lexorma.";

    private ClassLoader classLoader;

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** Spring Session JDBC 가 이 이름의 빈으로 세션 속성을 직렬화·역직렬화한다 */
    @Bean
    public ConversionService springSessionConversionService() {
        GenericConversionService conversionService = new GenericConversionService();
        conversionService.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversionService.addConverter(byte[].class, Object.class, new DeserializingConverter((Deserializer<Object>) this::deserialize));
        return conversionService;
    }

    private Object deserialize(InputStream inputStream) {
        try (ConfigurableObjectInputStream objects = new ConfigurableObjectInputStream(inputStream, classLoader) {
            @Override
            protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                ObjectStreamClass descriptor = super.readClassDescriptor();
                if (!descriptor.getName().startsWith(LEGACY_PACKAGE)) return descriptor;
                // 같은 클래스의 새 이름 : 필드는 그대로이므로 지금 클래스의 정의로 읽는다
                return ObjectStreamClass.lookup(Class.forName(PACKAGE + descriptor.getName().substring(LEGACY_PACKAGE.length()), false, classLoader));
            }
        }) {
            return objects.readObject();
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            log.warn("세션 값을 읽지 못해 없는 값으로 처리합니다 : {}", e.toString());
            return null;
        }
    }
}
