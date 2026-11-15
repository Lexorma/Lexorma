# H2 파일 DB 저장 (h2-local · 단일 JAR)

> 2026-11-14. PostgreSQL 없이 Lexorma 를 실행할 때(`h2-local`) 데이터가 재시작·JAR 교체 후에도 남도록 H2 를 **파일 모드**로 바꿨다.
> PostgreSQL 프로필(`local`·`dev`·`docker`)과 Docker H2 이미지(`h2-docker`)는 바꾸지 않았다.

## 1. 메모리 모드와 파일 모드

| | 메모리 (`jdbc:h2:mem:`) | 파일 (`jdbc:h2:file:`) |
|---|---|---|
| 데이터 | 프로세스 메모리. 종료하면 사라진다 | `<경로>.mv.db` 파일. 재시작·JAR 교체 후에도 남는다 |
| Flyway | 기동할 때마다 V0 부터 전부 다시 적용 | 처음 한 번 전부, 다음부터 새 버전만 (`flyway_schema_history` 가 파일에 있다) |
| 로그인 세션 (Spring Session JDBC) | 재시작하면 사라진다 | DB 에 남는다. 만료 정책(`server.servlet.session.timeout`)은 그대로 — 만료 전이면 재시작 후에도 로그인 상태 |
| 동시 사용 | 한 프로세스 | 한 프로세스 (파일 잠금). 여러 Lexorma 가 한 파일을 쓰는 구성은 지원하지 않는다 |

## 2. 실행 환경별 DB

| 실행 환경 | 프로필 | DB | 데이터 |
|---|---|---|---|
| 단일 JAR (Standalone) · PostgreSQL 없는 로컬 실행 | `h2-local` | **H2 파일** `<LEXORMA_DATA_DIR>/lexorma.mv.db` | 유지 |
| 로컬 개발 (기본) | `local` | PostgreSQL `localhost:15439` | DB 서버 |
| 개발 서버 | `dev` | PostgreSQL | DB 서버 |
| Docker (PostgreSQL) | `docker` | PostgreSQL (`DB_*`) | Volume |
| Docker (H2 이미지) | `h2-docker` | H2 파일 `/data/lexorma.mv.db` (전부터 파일 모드, 프로필은 바꾸지 않음) | Volume — `docker/lexorma/docker-compose.h2.yml` 은 `LEXORMA_DATA_DIR`(.env)를 `/data` 에 마운트 |
| 자동화 테스트 | `test` | H2 메모리 `jdbc:h2:mem:lexorma-test` | 테스트 JVM 안에서만. 사용자 H2 파일을 열지 않는다 |

`h2-local` 은 개발 편의 설정(H2 콘솔·Swagger·SQL 로그·공개된 개발용 암호화 키)이 함께 켜지는 프로필이다. 다른 사람이 쓰는 서버로 운영하려면 PostgreSQL(`docker`) 이나 `h2-docker` 이미지를 쓴다.

## 3. 저장 위치

```yaml
# application-h2-local.yml
spring.datasource.url: jdbc:h2:file:${LEXORMA_DATA_DIR:${user.home}/.lexorma/data}/lexorma;DB_CLOSE_ON_EXIT=FALSE
```

- **기본 : `<사용자 홈>/.lexorma/data/lexorma.mv.db`** (macOS `~/.lexorma/data`, Windows `C:\Users\<사용자>\.lexorma\data`). 실행 위치(작업 디렉터리)와 상관없이 같은 DB 를 연다 — `./data` 같은 상대 경로는 IntelliJ(보통 저장소 루트)·`./gradlew bootRun`(`lexorma-app/`)·`java -jar`(명령을 실행한 곳)마다 달라져 다른 빈 DB 가 생길 수 있어 기본값으로 쓰지 않았다.
- **바꾸기 (선택)** : 환경변수 `LEXORMA_DATA_DIR` 또는 `-DLEXORMA_DATA_DIR=…`. 예) JAR 옆에 두려면 `LEXORMA_DATA_DIR=/opt/lexorma/data java -jar lexorma.jar --spring.profiles.active=h2-local`. 상대 경로(`./data`)도 되지만 실행 위치 기준이다. 필수 설정은 없다.
- DB 파일은 JAR 밖에 있으므로 JAR 를 다시 빌드하거나 교체해도 그대로다.
- `DB_CLOSE_ON_EXIT=FALSE` : 정상 종료 때 Spring 이 연결 풀을 닫으면 마지막 연결과 함께 H2 가 DB 를 정리해 닫는다 (H2 의 JVM 종료 훅이 Spring 보다 먼저 DB 를 닫지 않게 한다). `DB_CLOSE_DELAY=-1` 은 파일 DB 에 두지 않는다 — 두면 연결이 다 닫혀도 DB 가 열린 채 JVM 이 끝나 정상 종료 때 파일을 정리하지 못한다.
- 저장소 안을 가리켜도 Git 에 올라가지 않는다 : `.gitignore` 의 `*.mv.db`·`*.trace.db`·`*.lock.db` (기존 `/data` 도 그대로).

### 시작할 때 점검 (`com.lexorma.config.H2FileDatabaseCheck`)

H2 파일 URL 일 때만, DataSource·Flyway 보다 먼저 :

1. 로그 `H2 파일 DB : <절대 경로>.mv.db (기존 DB | 처음 실행 — 새로 만든다)` — 계정·비밀번호는 남기지 않는다.
2. 디렉터리가 없으면 만든다. 만들 수 없거나 쓸 수 없으면 `H2 데이터 디렉터리를 만들 수 없습니다 / 쓸 수 없습니다 : <경로> (권한을 확인하거나 LEXORMA_DATA_DIR 로 다른 위치를 지정하세요)` 로 시작을 멈춘다.
3. DB 파일을 다른 프로세스가 잡고 있으면(두 번째 Lexorma, H2 도구) `H2 DB 파일을 다른 프로세스가 쓰고 있습니다 : <경로> (이미 실행 중인 Lexorma 나 H2 도구를 종료하거나, LEXORMA_DATA_DIR 로 다른 위치를 지정하세요)` 로 멈춘다.

## 4. 처음 실행 · 재시작

```bash
./gradlew :lexorma-app:bootJar
java -jar lexorma-app/build/libs/lexorma.jar --spring.profiles.active=h2-local     # 또는 ./gradlew bootRun --args='--spring.profiles.active=h2-local'
```

- 처음 : DB 파일을 만들고 Flyway 가 V0~최신을 적용한다 (`admin` / `admin`, 첫 로그인에서 비밀번호 변경).
- 다음부터 : 같은 파일을 열고 `Schema "PUBLIC" is up to date` — 새 버전이 있을 때만 그 버전을 적용한다. 테이블을 다시 만들거나 데이터를 초기화하지 않는다 (`ddl-auto: validate` 그대로).
- 종료는 정상 종료로 (`Ctrl+C`, `kill -TERM`, IDE 의 Stop). 강제 종료(`kill -9`, 전원 차단)하면 마지막 순간의 쓰기가 반영되지 않을 수 있다 — H2 가 다음 시작 때 파일을 복구한다.

### 이전 메모리 DB 의 데이터

메모리 모드에서 쓰던 데이터는 종료와 함께 사라지는 데이터라 파일 DB 로 옮겨지지 않는다 (URL 만 바꿔서는 이전되지 않는다). 이 변경 시점에 보존할 메모리 DB 는 없었다 : 사용자 개발 앱(9940)은 `local`(PostgreSQL)이고, `h2-local` 로 떠 있던 것은 검증용 임시 인스턴스뿐이었다. PostgreSQL 데이터를 H2 로 복사하는 기능은 없다 (범위 밖).

## 5. 백업 · 복원

- **오프라인 백업 (권장)** : Lexorma 를 정상 종료한 뒤 `lexorma.mv.db` 를 복사한다. 다른 디스크·기기에 보관한다.
- **온라인 백업** : 실행 중인 파일을 그대로 복사하면 쓰는 도중의 상태가 담길 수 있다. 실행 중에는 H2 의 백업 명령을 쓴다 — `h2-local` 은 H2 콘솔(`/h2-console`, JDBC URL 은 시작 로그의 경로)이 켜져 있다 :
  - `BACKUP TO '/backup/lexorma-20261114.zip'` : DB 파일을 일관된 상태로 zip 에 담는다.
  - `SCRIPT TO '/backup/lexorma-20261114.sql'` : SQL 스크립트 (H2 버전이 바뀔 때도 쓸 수 있는 형식).
- **복원** : Lexorma 를 **종료한 뒤** `lexorma.mv.db` 를 백업 파일로 바꾼다 (zip 이면 풀어서). SQL 스크립트는 빈 위치에서 `RUNSCRIPT FROM '…'` 로 넣는다 (그다음 Lexorma 를 시작하면 Flyway 이력도 스크립트에 들어 있어 다시 적용하지 않는다).
- **H2 버전** : 지금 H2 2.4.240. `.mv.db` 는 같은 메이저 버전(2.x)끼리 연다. H2 를 메이저 버전으로 올릴 때는 올리기 전에 `SCRIPT TO` 로 내보내고 새 버전에서 `RUNSCRIPT` 로 넣는다.

## 6. 문제 해결

| 증상 | 확인 |
|---|---|
| 시작이 `다른 프로세스가 쓰고 있습니다` 로 멈춤 | 같은 DB 로 이미 떠 있는 Lexorma(다른 터미널·IDE), H2 콘솔 도구. 하나만 남기거나 `LEXORMA_DATA_DIR` 로 나눈다 |
| `데이터 디렉터리를 만들 수 없습니다 / 쓸 수 없습니다` | 그 경로의 권한 (Windows : 사용자 폴더 밖·보호된 폴더) |
| 재시작했는데 데이터가 없다 | 시작 로그의 `H2 파일 DB :` 경로가 이전 실행과 같은지 (`LEXORMA_DATA_DIR` 이 상대 경로이거나 다른 사용자 계정으로 실행) |
| 자동화 테스트 | `test` 프로필은 메모리 DB 라 사용자 H2 파일과 무관하다 |

### Docker Compose (H2)

`docker/lexorma/docker-compose.h2.yml` : PostgreSQL 없이 H2 이미지(`lexorma/lexorma:h2-latest`, `h2-docker`)만 띄운다. 값은 PostgreSQL Compose 와 같은 `.env` 에서 읽는다 (`LEXORMA_DATA_DIR` → `/data`, `LEXORMA_H2_IMAGE`, `LEXORMA_PORT`, 선택 암호화 키는 같은 Docker secret 방식).

- `SPRING_DATASOURCE_URL=jdbc:h2:file:/data/lexorma;DB_CLOSE_ON_EXIT=FALSE` 로 프로필의 같은 파일을 연다 — `h2-docker` 프로필 URL 의 `DB_CLOSE_DELAY=-1` 만 빼서 정상 종료 때 파일을 닫는다 (기존 `/data/lexorma.mv.db` 그대로 쓴다).
- `stop_grace_period: 30s` : `docker compose stop` 때 연결 풀을 닫을 시간을 준다. 한 데이터 디렉터리는 한 컨테이너만 쓴다.
- 검증 (2026-11-14) : 이미지 빌드 → `up` (V0~V9 적용) → 비밀번호 변경·SEC-1 규칙·AI Provider·시스템 기본·테이블 등록 → `stop`(정상 종료) → `down`(컨테이너 삭제) → `up`(새 컨테이너) : `기존 DB`, `No migration necessary`, 등록한 것·로그인 세션 유지.

## 7. 검증 (2026-11-14, macOS)

- `H2FilePersistenceTest` (4개) : `application-h2-local.yml` 의 실제 URL(기본·`LEXORMA_DATA_DIR`), 테스트 프로필이 메모리인지, 임시 디렉터리의 H2 파일에 Flyway 전부 적용 → 데이터 저장 → 닫기 → 다시 열기(마이그레이션 0건, 이력·데이터·AI Provider·기본 Provider 유지), 잠금·권한 오류. 전체 애플리케이션을 테스트 JVM 에서 다시 띄우지 않는다 (정적 상태가 캐시된 다른 테스트 컨텍스트에 영향을 줄 수 있다).
- 단일 JAR 실제 재시작 (`-Duser.home` 을 임시 폴더로, 포트 9948) : 처음 실행에서 `~/.lexorma/data/lexorma.mv.db` 생성·V0~V9 적용 → admin 비밀번호 변경, 프로젝트·표준 단어·도메인·용어·테이블·컬럼·SEC-1 허용 규칙·AI Provider·시스템 기본 Provider 등록 → `kill -TERM`(정상 종료, `.trace.db` 없음) → **다른 디렉터리의 새 JAR 복사본**으로 재시작 : `기존 DB`, `Schema "PUBLIC" is up to date`, 등록한 것 모두 그대로, 초기 비밀번호는 거부·바꾼 비밀번호로 로그인, 종료 전 로그인 세션도 유지. 같은 DB 로 두 번째 실행은 잠금 오류로 멈춤, `LEXORMA_DATA_DIR=./data` 는 실행 위치의 `data/` 에 새 DB.
- **Windows 는 실제로 검증하지 않았다** (경로·권한·잠금 동작은 Java NIO·H2 공통 코드지만 미검증).
