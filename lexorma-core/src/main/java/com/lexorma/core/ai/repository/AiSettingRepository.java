package com.lexorma.core.ai.repository;

import com.lexorma.core.ai.domain.entity.AiSetting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AiSettingRepository extends JpaRepository<AiSetting, Long> {

    /** 설정 행을 배타로 잡는다 (기본 Provider 지정·해제, Provider 삭제가 이 행에서 차례를 지킨다) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AiSetting s where s.id = " + AiSetting.SYSTEM)
    Optional<AiSetting> lockSystem();
}
