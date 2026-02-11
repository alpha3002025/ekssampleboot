package live.overtake.ekssampleboot.secret;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@RestController
public class SecretController {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SecretsManagerClient client = SecretsManagerClient.builder()
            .region(Region.AP_NORTHEAST_2)
            .build();

    @GetMapping("/")
    public String home(){
        return "Hello World! (Pod Identity Demo)";
    }

    @GetMapping("/secret")
    public Map<String,Object> getSecret(){
        try {
            // 1. Secrets Manager에서 "ekssampleboot/secret" 값을 조회
            GetSecretValueResponse response = client.getSecretValue(builder -> builder.secretId("ekssampleboot/secret"));

            // 2. JSON 문자열 파싱 (Secrets Manager는 기본적으로 JSON String형태로 저장됨)
            // 예: "{\"secret\":\"Decrypted_Secret_Value_1234\"}" -> Map 변환
            String secretJson = response.secretString();
            Map<String, Object> secretMap = objectMapper.readValue(secretJson, new TypeReference<>() {});

            return Map.of("message", "OK", "secret_data", secretMap);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
