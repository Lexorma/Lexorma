package com.lexorma.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * H2 파일 DB(h2-local · h2-docker) 를 열기 전에 저장 위치를 확인한다. DataSource·Flyway 보다 먼저 돈다 (BeanFactoryPostProcessor).
 * <ul>
 *   <li>DB 파일 경로를 로그로 남긴다 (계정·비밀번호는 남기지 않는다)</li>
 *   <li>디렉터리가 없으면 만들고, 만들 수 없거나 쓸 수 없으면 경로와 바꾸는 방법(LEXORMA_DATA_DIR)을 담은 오류로 시작을 멈춘다</li>
 *   <li>다른 프로세스(두 번째 Lexorma, H2 콘솔 도구 등)가 그 DB 파일을 잡고 있으면 같은 방식으로 멈춘다 — 한 DB 파일은 한 프로세스만 쓴다</li>
 * </ul>
 * 메모리 DB·PostgreSQL 이면 아무것도 하지 않는다.
 */
@Slf4j
@Component
public class H2FileDatabaseCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final String PREFIX = "jdbc:h2:file:";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment.getProperty("spring.datasource.url"));
    }

    /** 확인 (H2 파일 DB 가 아니면 아무것도 하지 않는다). 실패하면 IllegalStateException */
    public static void check(String url) {
        Path database = databaseFile(url);
        if (database == null) return;
        Path directory = database.getParent();
        try {
            Files.createDirectories(directory);
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("H2 데이터 디렉터리를 만들 수 없습니다 : " + directory
                    + " (권한을 확인하거나 LEXORMA_DATA_DIR 로 다른 위치를 지정하세요)", e);
        }
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new IllegalStateException("H2 데이터 디렉터리에 쓸 수 없습니다 : " + directory
                    + " (권한을 확인하거나 LEXORMA_DATA_DIR 로 다른 위치를 지정하세요)");
        }
        Path file = Path.of(database + ".mv.db");
        if (Files.exists(file)) checkNotInUse(file);
        log.info("H2 파일 DB : {} ({})", file, Files.exists(file) ? "기존 DB" : "처음 실행 — 새로 만든다");
    }

    /** jdbc:h2:file:경로;옵션 → 절대 경로 (확장자 없음). H2 처럼 ~ 를 사용자 홈으로 본다. 파일 DB 가 아니면 null */
    public static Path databaseFile(String url) {
        if (url == null || !url.startsWith(PREFIX)) return null;
        String path = url.substring(PREFIX.length());
        int options = path.indexOf(';');
        if (options >= 0) path = path.substring(0, options);
        if (path.startsWith("~")) path = System.getProperty("user.home") + path.substring(1);
        return Path.of(path).toAbsolutePath().normalize();
    }

    /** H2(MVStore)는 DB 파일에 잠금을 건다 : 잠깐 잠가 보고 바로 푼다. 잠글 수 없으면 다른 프로세스가 쓰는 중이다 */
    private static void checkNotInUse(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) throw inUse(file, null);
            lock.release();
        } catch (OverlappingFileLockException e) {
            throw inUse(file, e);
        } catch (IOException e) {
            throw new IllegalStateException("H2 DB 파일을 열 수 없습니다 : " + file + " (권한을 확인하세요)", e);
        }
    }

    private static IllegalStateException inUse(Path file, Exception cause) {
        return new IllegalStateException("H2 DB 파일을 다른 프로세스가 쓰고 있습니다 : " + file
                + " (이미 실행 중인 Lexorma 나 H2 도구를 종료하거나, LEXORMA_DATA_DIR 로 다른 위치를 지정하세요)", cause);
    }
}
