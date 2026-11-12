package com.lexorma.source.tool;

import com.lexorma.core.project.domain.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 등록된 내부 도구 (Spring 빈으로 만든 {@link SourceMetadataTool} 전부). 이름과 DBMS 로 찾는다.
 * 같은 이름·같은 DBMS 를 맡는 구현이 둘이면 어느 것이 실행될지 알 수 없으므로 기동할 때 막는다.
 * 지금은 등록된 도구가 없다 (구현은 AI-2). 도구가 하나도 없어도 애플리케이션은 그대로 뜬다.
 */
@Component
public class SourceToolRegistry {

    private final List<SourceMetadataTool<?, ?>> tools;

    public SourceToolRegistry(List<SourceMetadataTool<?, ?>> tools) {
        for (DbType dbType : DbType.values()) {
            List<String> names = tools.stream().filter(tool -> tool.supports(dbType)).map(SourceMetadataTool::name).toList();
            names.stream().filter(name -> names.indexOf(name) != names.lastIndexOf(name)).findFirst().ifPresent(name -> {
                throw new IllegalStateException("Duplicate source tool for " + dbType + ": " + name);
            });
        }
        this.tools = List.copyOf(tools);
    }

    public Optional<SourceMetadataTool<?, ?>> find(String name, DbType dbType) {
        return tools.stream().filter(tool -> tool.name().equals(name) && tool.supports(dbType)).findFirst();
    }

    /** 이 DBMS 에서 실행할 수 있는 도구 이름 */
    public List<String> names(DbType dbType) {
        return tools.stream().filter(tool -> tool.supports(dbType)).map(SourceMetadataTool::name).sorted().toList();
    }
}
