# GitHub Actions Workflow & IAM Role Configuration (2026-02-07)

## 1. GitHub Actions Workflow

`ekssampleboot/.github/workflows/deploy_application.yaml`

이 워크플로우는 AWS IAM Role을 Assume하여 ECR에 이미지를 배포합니다.

```yaml
name: Deploy to ECR

on:
  push:
    branches: [ "main" ]

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    permissions:
      id-token: write # AWS OIDC 인증을 위해 반드시 필요 (JWT 토큰 발급)
      contents: read

    steps:
    - uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Configure AWS credentials
      uses: aws-actions/configure-aws-credentials@v4
      with:
        # GitHub Secrets에 AWS_ROLE_ARN을 저장해야 함
        # 예: arn:aws:iam::123456789012:role/my-github-actions-role
        role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
        aws-region: ap-northeast-2

    - name: Login to Amazon ECR
      id: login-ecr
      uses: aws-actions/amazon-ecr-login@v2

    - name: Build and Push with Jib
      # Jib 플러그인이 ECR URL을 인식하여 이미지를 푸시하도록 환경변수 주입
      env:
        ECR_URL: ${{ steps.login-ecr.outputs.registry }}
      run: ./gradlew jib
```

---

## 2. AWS IAM Role 설정 (필수)

GitHub Actions가 AWS에 접근하려면 **OIDC Provider**를 통한 인증을 사용하는 것이 보안상 가장 좋습니다. (Access Key/Secret Key 발급 불필요)

### A. OIDC Provider 생성 (최초 1회)
AWS IAM 콘솔 -> Identity providers -> Add provider

- **Provider Type**: OpenID Connect
- **Provider URL**: `https://token.actions.githubusercontent.com`
- **Audience**: `sts.amazonaws.com`

**권장 방식: Terraform (IaC)**

AWS 콘솔보다 **Terraform으로 관리하는 것을 강력하게 권장**합니다. 코드(Git)로 변경 이력을 관리할 수 있고, 재사용 및 협업에 유리하기 때문입니다.

아래는 **Thumbprint 자동 조회부터 IAM Role 생성까지** 한 번에 처리하는 전체 Terraform 코드 예시입니다.

```hcl
# 1. GitHub OIDC의 TLS 인증서 정보 조회 (Thumbprint 자동 획득)
data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

# 2. OIDC Provider 생성
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
}

# 3. IAM Role 생성 (Trust Policy 포함)
resource "aws_iam_role" "github_actions" {
  name = "my-github-actions-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRoleWithWebIdentity"
        Effect = "Allow"
        Principal = {
          Federated = aws_iam_openid_connect_provider.github.arn
        }
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          }
          StringLike = {
            # 본인의 github org와 repo로 수정 필수!
            "token.actions.githubusercontent.com:sub" = "repo:my-org/my-repo:*" 
          }
        }
      }
    ]
  })
}

# 4. ECR 권한 정책 생성 및 연결
resource "aws_iam_role_policy" "ecr_policy" {
  name = "github-actions-ecr-policy"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage"
        ]
        Resource = "*" # 특정 ECR ARN으로 제한 권장
      }
    ]
  })
}

# 5. 생성된 Role ARN 출력 (GitHub Secrets에 등록할 값)
output "role_arn" {
  value = aws_iam_role.github_actions.arn
}
```

---

### B. IAM Role 생성 및 신뢰 관계 (Trust Relationship) (콘솔 사용 시 참고)
*(Terraform 사용 시 위 코드로 자동 처리되므로 이 과정은 생략 가능)*

### B. IAM Role 생성 및 신뢰 관계 (Trust Relationship)

IAM Role을 생성할 때, 아래의 **Trust Relationship** 정책을 설정해야 합니다.

**조건:**
- `Federated`: 위에서 생성한 OIDC Provider의 ARN
- `Action`: `sts:AssumeRoleWithWebIdentity`
- `Condition`:
    - `aud`: `sts.amazonaws.com`
    - `sub`: `repo:<GITHUB_ORG>/<REPO_NAME>:*` (특정 리포지토리만 허용)

**Trust Policy (JSON):**
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
            },
            "Action": "sts:AssumeRoleWithWebIdentity",
            "Condition": {
                "StringEquals": {
                    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
                },
                "StringLike": {
                    "token.actions.githubusercontent.com:sub": "repo:<MY-ORG>/<MY-REPO>:*"
                }
            }
        }
    ]
}
```

### C. IAM Role 권한 (Permissions)

이 Role이 수행할 작업(ECR Push)에 필요한 권한을 부여합니다.

**Permission Policy (JSON):**
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "ecr:GetAuthorizationToken",
                "ecr:BatchCheckLayerAvailability",
                "ecr:GetDownloadUrlForLayer",
                "ecr:BatchGetImage",
                "ecr:InitiateLayerUpload",
                "ecr:UploadLayerPart",
                "ecr:CompleteLayerUpload",
                "ecr:PutImage"
            ],
            "Resource": "*"
        }
    ]
}
```
*(참고: `Resource`를 특정 ECR 리포지토리 ARN으로 제한하면 보안이 강화됩니다.)*

---

## 3. GitHub Secrets 설정

GitHub 리포지토리 -> Settings -> Secrets and variables -> Actions -> **New repository secret**

- **Name**: `AWS_ROLE_ARN`
- **Value**: 생성한 IAM Role의 ARN (예: `arn:aws:iam::123456789012:role/my-github-actions-role`)


## Questions
### Q1. arn:aws:iam::123456789012:role/my-github-actions-role 같은 ARN만 명시하면 어떤 사람이든 GitHub Actions 내에서 다 접근 가능한 건가?

**답변:** 
굉장히 중요한 보안 질문입니다. **결론부터 말씀드리면, "아니요, 아무나 접근할 수 없습니다."**

단순히 `arn:aws:iam::123456789012:role/my-github-actions-role`이라는 ARN을 안다고 해서 그 역할을 사용할 수 있는 것은 아닙니다. **IAM Role의 신뢰 정책(Trust Policy)이 강력한 문지기 역할**을 하기 때문입니다.

### 핵심은 `Trust Relationship` (신뢰 관계)
제가 정리해드린 문서의 "IAM Role 설정" 부분을 보시면, `Condition` (조건) 블록이 있습니다.

```json
"Condition": {
    "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
    },
    // 🔥 여기가 핵심입니다!
    "StringLike": {
        "token.actions.githubusercontent.com:sub": "repo:<MY-ORG>/<MY-REPO>:*"
    }
}
```

1.  **GitHub가 보증하는 토큰인가?**:
    `Principal`이 GitHub OIDC Provider로 설정되어 있어서, GitHub Actions가 발행한 유효한 OIDC 토큰을 가진 요청만 일단 받아들입니다.

2.  **"내 리포지토리"에서 온 요청인가?**:
    `sub` (Subject) 필드를 확인합니다. 여기에 `repo:my-org/my-repo:*` 처럼 **특정 조직(Organization)과 특정 리포지토리(Repository)**를 명시해야 합니다.

**따라서:**
*   **다른 사람**이 본인의 GitHub Actions 코드에 님꺼 Role ARN (`arn:aws:...`)을 적어서 실행하더라도,
*   AWS는 요청을 보낸 쪽의 토큰 정보를 까보고 **"어? 이거 `my-org/my-repo`에서 온 게 아니라 `hacker/repo`에서 왔네?"** 하고 **거절(AccessDenied)**합니다.

**즉, ARN이 공개되어도 안전합니다.** (물론 굳이 공개할 필요는 없으니 Secrets에 넣는 것이 관례입니다.)

---

**[주의사항]** 만약 `StringLike` 조건을 `repo:*:*` 처럼 와일드카드로 너무 넓게 열어두면... **전 세계 모든 GitHub 리포지토리**에서 님 역할을 사용할 수 있게 됩니다. 😱 절대 이렇게 하시면 안 됩니다! **반드시 본인의 `Org/Repo` 이름으로 제한하세요.**

### Q2. `data "tls_certificate"` 부분은 내부적으로 어떻게 동작하여 `certificates` 값을 가져오나요?

**답변:**
Terraform의 `tls` provider가 수행하는 동작은 단순히 JSON을 파싱하는 것이 아니라, **HTTPS 연결을 통해 SSL/TLS 인증서 체인을 직접 검증하고 추출**하는 과정입니다.

1.  **HTTPS 연결:** 지정된 `url` (GitHub OIDC 설정 주소)로 HTTPS 요청을 보냅니다.
2.  **인증서 체인 획득:** 서버가 응답할 때 제공하는 **X.509 인증서 체인(Certificate Chain)**을 받아옵니다. (Root CA, Intermediate CA, Server Certificate 등)
3.  **지문 계산 (Hashing):** 받아온 인증서들 중 최상위(또는 지정된) 인증서의 **SHA1 지문(Fingerprint / Thumbprint)**을 내부적으로 계산합니다.
4.  **속성 제공:** 계산된 값을 `certificates[0].sha1_fingerprint` 같은 속성으로 Terraform 코드에서 사용할 수 있게 제공합니다.

AWS OIDC Provider 설정 시 보안을 위해 이 **서버 인증서의 지문(Thumbprint)**을 등록해야 하는데, 이 과정을 Terraform이 자동으로 처리해 줍니다. 덕분에 나중에 GitHub의 인증서가 갱신되어 지문이 바뀌더라도 `terraform apply`만 다시 수행하면 자동으로 최신 값을 가져오게 됩니다.
