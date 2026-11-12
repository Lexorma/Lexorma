package com.lexorma.core.logs.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "TB_LOG")
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Logs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LOG_SN")
    private Long id;

    @Column(name = "DMND_URL_ADDR", nullable = false)
    private String logUrl; // 요청 URL

    @Column(name = "DMND_MTHD_NM", nullable = false)
    private String method; // GET, POST 등

    @Column(name = "CNTN_IP_ADDR", nullable = false)
    private String ip; // 요청한 클라이언트 IP

    @Column(name = "PRJ_SN")
    private Long projectId;

    @Column(name = "USER_ID")
    private String username; // 사용자 아이디 (로그인 사용자)

    @Column(name = "ROLE_NM")
    private String roleName; // 권한명

    @Column(name = "RSPNS_STTS_CD")
    private Integer statusCode; // 응답 HTTP 상태 코드

    @Column(name = "DMND_CN", columnDefinition = "TEXT")
    private String requestBody; // 요청 데이터 (JSON 형태로 저장 가능)

    @Column(name = "RSPNS_CN", columnDefinition = "TEXT")
    private String responseBody; // 응답 데이터 (JSON 형태로 저장 가능)

    @Column(name = "ERR_CN", columnDefinition = "TEXT")
    private String errorMessage; // 예외 발생 시 에러 메시지

    @Column(name = "USER_AGNT_CN")
    private String userAgent; // 브라우저, OS 정보

    @Column(name = "LOG_DT", nullable = false)
    private LocalDateTime timestamp; // 요청 발생 시간

    @PrePersist
    public void setTimestamp() {
        this.timestamp = LocalDateTime.now();
    }

}
