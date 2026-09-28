# DailyOS

투자 · 건강 · 일본어 · 커리어 루틴을 **폰 2대 + PC**에서 함께 관리하는 개인용 앱

```
[메인폰 앱] ─┐
[서브폰 앱] ─┼──▶ Supabase (클라우드 DB) ◀── [PC 웹 대시보드]
```

| 구성 | 하는 일 |
|---|---|
| **안드로이드 앱** | 정해진 시각에 루틴 알림 → 알림에서 바로 `완료 ✓` / 숫자 입력 / `30분 뒤`<br>15분마다 앱 사용량(유튜브 등) 자동 업로드 · 두 폰 합산 한도 80%/100% 경고<br>한 폰에서 체크하면 다른 폰의 알림도 사라짐 (최대 15분 이내) |
| **웹 대시보드** | 오늘 체크 · 지난 날짜 소급 기록 · 영역별 달성률 추이 · 히트맵 · 연속 기록 · 주간 요약 · 앱 사용량 차트 · 루틴/한도 설정 |
| **Supabase** | 모든 기록 저장, 본인 계정만 접근 가능 (RLS) |

---

## 처음 설치하기 (약 20분, 한 번만)

### 1단계. Supabase 준비

1. Supabase 프로젝트 → 왼쪽 **SQL Editor** → `supabase/schema.sql` 내용을 전부 붙여넣고 **Run**
2. **Authentication → Users → Add user → Create new user**
   - 로그인에 쓸 이메일과 비밀번호 입력, **Auto Confirm User** 체크
3. **Authentication → Sign In / Providers** → **Allow new users to sign up** 끄기
   (웹 주소를 아는 다른 사람이 가입하지 못하게 막아요)
4. **Project Settings → API**에서 `Project URL`과 `anon`(또는 `publishable`) 키를 복사해 두기

### 2단계. GitHub에 코드 올리기

1. GitHub 오른쪽 위 **+ → New repository**
   - 이름: `dailyos` · **Public** 선택 (무료로 웹 대시보드를 호스팅하려면 Public이 필요해요. 코드만 공개되고 데이터는 공개되지 않아요)
   - **Create repository**
2. 생성된 페이지에서 **uploading an existing file** 클릭
3. 압축을 푼 `dailyos` 폴더 **안의 내용물 전부**(`.github`, `android`, `supabase`, `web`, `README.md` 등)를 드래그해서 올리고 **Commit changes**
   - `.github` 폴더가 꼭 포함돼야 해요. 안 보이면 탐색기 **보기 → 숨긴 항목**을 켜세요.
   - 첫 업로드 직후 빌드가 한 번 실패하는데, 아직 시크릿을 넣지 않아서 그런 거니 정상이에요.

### 3단계. 시크릿 4개 등록

저장소 **Settings → Secrets and variables → Actions → New repository secret**에서 하나씩 추가하세요.

| Name | Value |
|---|---|
| `SUPABASE_URL` | https://xxxx.supabase.co |
| `SUPABASE_KEY` | anon / publishable 키 |
| `KEYSTORE_B64` | 따로 받은 `secrets.txt`의 긴 문자열 |
| `KEYSTORE_PASSWORD` | `secrets.txt`의 비밀번호 |

> `secrets.txt`는 앱 서명 키예요. 다른 곳에 백업해 두세요. 잃어버리면 앱을 지우고 다시 설치해야 해요.

### 4단계. 웹 대시보드 켜고 빌드 실행

1. **Settings → Pages → Build and deployment → Source: GitHub Actions**
2. **Actions** 탭 → 왼쪽 **Build & Deploy** → **Run workflow** → 초록 체크가 뜰 때까지 기다리기 (약 5분)

### 5단계. 폰 2대에 설치

1. 폰 브라우저로 `https://github.com/<내아이디>/dailyos/releases/latest`에 접속 → **DailyOS.apk** 받기 → 설치
   (처음이면 "출처를 알 수 없는 앱 설치 허용"이 뜨니 허용하세요)
2. 앱을 열어 로그인하고, 폰 이름을 입력하세요 (예: 메인폰 / 서브폰)
3. 화면의 **권한 설정** 버튼을 전부 눌러 허용하세요
   - 알림 · 사용량 접근 · 정확한 시간 알림 · 배터리 최적화 제외
   - 삼성 폰은 **설정 → 배터리 → 백그라운드 사용 제한**에서 DailyOS를 "절전 예외 앱"에 추가하면 알림이 더 안정적이에요.
4. 두 번째 폰도 똑같이 하세요

### 6단계. PC 대시보드

`https://<내아이디>.github.io/dailyos/` → 로그인 → **설정** 탭 → **기본 템플릿으로 시작하기**
→ 항목 이름, 알림 시각, 목표를 내 루틴에 맞게 바꾸세요 (예: "영양제 (아침)" → "비타민D + 오메가3").
폰에는 앱을 열거나 15분 안에 반영돼요.

---

## 매일 쓰는 법

- **체크 항목**: 알림의 `완료 ✓`를 누르면 끝이에요
- **수치 항목** (단어 수 등): 알림의 `기록하기`를 누르고 숫자 입력 → 오늘 값에 **더해져요** (20 + 15 = 35)
- **`30분 뒤`**: 30분 후에 다시 알려줘요
- 폰이 오프라인이어도 기록은 저장되고, 연결되면 자동으로 올라가요
- 앱 사용량 한도는 PC **설정 → 앱 사용 한도**에서 추가하세요 (최근 쓴 앱이 목록에 떠요)

## 업데이트하기

코드를 바꾼 파일을 GitHub에 다시 올리면 자동으로 새 APK와 웹이 배포돼요.
폰에서는 `releases/latest`에서 새 APK를 받아 덮어 설치하면 돼요 (기록과 로그인은 그대로 남아요).

## 폴더 구조

```
supabase/schema.sql         DB 테이블 + 보안 정책 + 수치 누적 함수
android/                    안드로이드 앱 (Kotlin, 외부 라이브러리 없음)
web/                        PC 웹 대시보드 (HTML/JS, Chart.js)
.github/workflows/build.yml APK 빌드 → Releases, 웹 → GitHub Pages
```
