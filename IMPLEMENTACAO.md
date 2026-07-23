# Guia de Implementação — Notificador WhatsApp

## Decisões já tomadas

| Ponto | Decisão |
|---|---|
| Mapeamento dia→checkboxes | Fixo em classe Java (não banco) |
| Disparo | Endpoint externo `POST /notify` (Cloud Scheduler chama) |
| Firebase | Ler checkboxes do Firestore para saber se estudou |
| Segurança | Chave simples via header `X-Notify-Key` |

---

## Contexto do Firestore

Projeto Firebase: `rotina-semanal-af274`

Coleção: `semanas/{weekDocKey}` onde `weekDocKey` = `"YYYY-WNN"` (ex: `"2026-W30"`)

Documento:
```json
{
  "checkboxes": {
    "seg-java":  true,
    "seg-prat":  false,
    "ter-js":    false,
    "ter-prat":  false,
    "qua-proj":  false,
    "qui-java":  false,
    "qui-prat":  false,
    "sex-mysql": false
  }
}
```

---

## Mapeamento dia → checkboxes (fixo no código)

```
Segunda  → seg-java, seg-prat
Terça    → ter-js,   ter-prat
Quarta   → qua-proj
Quinta   → qui-java, qui-prat
Sexta    → sex-mysql
Sáb/Dom  → não enviar nada
```

---

## Cálculo do weekDocKey — CRÍTICO

O front-end usa algoritmo customizado (não ISO week). Replicar exatamente:

```java
public static String calcWeekDocKey(LocalDate date) {
    LocalDate jan1 = LocalDate.of(date.getYear(), 1, 1);
    long daysSince = ChronoUnit.DAYS.between(jan1, date);
    // JS getDay(): Sun=0...Sat=6 | Java getValue(): Mon=1...Sun=7
    int jan1js = jan1.getDayOfWeek().getValue() % 7;
    int week   = (int) Math.ceil((daysSince + jan1js + 1.0) / 7);
    return date.getYear() + "-W" + week;
}
```

---

## Estrutura de pacotes

```
src/main/java/com/alvarofgomes/notificador/
│
├── domain/
│   ├── DayTrackMap.java              ← constante: dia → lista de track IDs
│   ├── StudyPort.java                ← interface: boolean estudouHoje(docKey, dia)
│   ├── NotificationPort.java         ← interface: void enviarLembrete()
│   └── CheckStudyUseCase.java        ← lógica pura, sem framework
│
├── infrastructure/
│   ├── firestore/
│   │   ├── FirebaseConfig.java       ← inicializa FirebaseApp
│   │   ├── SemanaDoc.java            ← POJO do documento
│   │   └── FirestoreStudyAdapter.java ← implementa StudyPort
│   └── twilio/
│       └── TwilioAdapter.java        ← implementa NotificationPort
│
├── application/
│   └── NotifyController.java         ← POST /notify com validação da chave
│
└── NotificadorApplication.java
```

---

## Etapa 1 — Domínio (sem dependência externa)

### DayTrackMap.java
```java
package com.alvarofgomes.notificador.domain;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

public class DayTrackMap {
    public static final Map<DayOfWeek, List<String>> MAP = Map.of(
        DayOfWeek.MONDAY,    List.of("seg-java", "seg-prat"),
        DayOfWeek.TUESDAY,   List.of("ter-js",   "ter-prat"),
        DayOfWeek.WEDNESDAY, List.of("qua-proj"),
        DayOfWeek.THURSDAY,  List.of("qui-java", "qui-prat"),
        DayOfWeek.FRIDAY,    List.of("sex-mysql")
    );
}
```

### StudyPort.java
```java
package com.alvarofgomes.notificador.domain;

import java.time.DayOfWeek;

public interface StudyPort {
    boolean estudouHoje(String weekDocKey, DayOfWeek dia);
}
```

### NotificationPort.java
```java
package com.alvarofgomes.notificador.domain;

public interface NotificationPort {
    void enviarLembrete();
}
```

### CheckStudyUseCase.java
```java
package com.alvarofgomes.notificador.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public class CheckStudyUseCase {

    private final StudyPort studyPort;
    private final NotificationPort notificationPort;

    public CheckStudyUseCase(StudyPort studyPort, NotificationPort notificationPort) {
        this.studyPort = studyPort;
        this.notificationPort = notificationPort;
    }

    public void execute(LocalDate hoje) {
        DayOfWeek dia = hoje.getDayOfWeek();
        if (dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY) return;

        String docKey = calcWeekDocKey(hoje);
        if (!studyPort.estudouHoje(docKey, dia)) {
            notificationPort.enviarLembrete();
        }
    }

    public static String calcWeekDocKey(LocalDate date) {
        LocalDate jan1 = LocalDate.of(date.getYear(), 1, 1);
        long daysSince = ChronoUnit.DAYS.between(jan1, date);
        int jan1js = jan1.getDayOfWeek().getValue() % 7;
        int week   = (int) Math.ceil((daysSince + jan1js + 1.0) / 7);
        return date.getYear() + "-W" + week;
    }
}
```

---

## Etapa 2 — Testes do domínio

```java
// CheckStudyUseCaseTest.java
class CheckStudyUseCaseTest {

    @Test
    void naoEnviaSeEstudou() { ... }

    @Test
    void enviaSeNaoEstudou() { ... }

    @Test
    void naoEnviaNoFimDeSemana() { ... }

    @Test
    void weekDocKeyCorreto() {
        // Validar com uma data conhecida e comparar com o valor que o front geraria
        assertEquals("2026-W30", CheckStudyUseCase.calcWeekDocKey(LocalDate.of(2026, 7, 23)));
    }
}
```

---

## Etapa 3 — FirebaseConfig + Adapter Firestore

### application.properties
```properties
firebase.credentials-path=${GOOGLE_APPLICATION_CREDENTIALS}
twilio.account-sid=${TWILIO_ACCOUNT_SID}
twilio.auth-token=${TWILIO_AUTH_TOKEN}
twilio.from=${TWILIO_FROM}
twilio.to=${TWILIO_TO}
notify.secret-key=${NOTIFY_SECRET_KEY}
```

### FirebaseConfig.java
```java
@Configuration
public class FirebaseConfig {

    @Value("${firebase.credentials-path}")
    private String credentialsPath;

    @Bean
    public Firestore firestore() throws IOException {
        GoogleCredentials credentials = GoogleCredentials
            .fromStream(new FileInputStream(credentialsPath));

        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setProjectId("rotina-semanal-af274")
            .build();

        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options);
        }

        return FirestoreClient.getFirestore();
    }
}
```

### SemanaDoc.java
```java
public class SemanaDoc {
    private Map<String, Boolean> checkboxes;
    // getters/setters
}
```

### FirestoreStudyAdapter.java
```java
@Component
public class FirestoreStudyAdapter implements StudyPort {

    private final Firestore firestore;

    public FirestoreStudyAdapter(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public boolean estudouHoje(String weekDocKey, DayOfWeek dia) {
        try {
            DocumentSnapshot snap = firestore
                .collection("semanas")
                .document(weekDocKey)
                .get().get();

            if (!snap.exists()) return false;

            Map<String, Boolean> checkboxes = (Map<String, Boolean>) snap.get("checkboxes");
            if (checkboxes == null) return false;

            List<String> tracks = DayTrackMap.MAP.getOrDefault(dia, List.of());
            return tracks.stream().anyMatch(t -> Boolean.TRUE.equals(checkboxes.get(t)));

        } catch (Exception e) {
            throw new RuntimeException("Erro ao ler Firestore", e);
        }
    }
}
```

---

## Etapa 4 — TwilioAdapter

```java
@Component
public class TwilioAdapter implements NotificationPort {

    @Value("${twilio.account-sid}") private String accountSid;
    @Value("${twilio.auth-token}")  private String authToken;
    @Value("${twilio.from}")        private String from;
    @Value("${twilio.to}")          private String to;

    @Override
    public void enviarLembrete() {
        Twilio.init(accountSid, authToken);
        Message.creator(new PhoneNumber(to), new PhoneNumber(from),
            "Oi! Você ainda não registrou estudo hoje. Acesse o Rotina Semanal e marque suas sessões.")
            .create();
    }
}
```

---

## Etapa 5 — Controller

```java
@RestController
public class NotifyController {

    @Value("${notify.secret-key}")
    private String secretKey;

    private final CheckStudyUseCase useCase;

    public NotifyController(StudyPort studyPort, NotificationPort notificationPort) {
        this.useCase = new CheckStudyUseCase(studyPort, notificationPort);
    }

    @PostMapping("/notify")
    public ResponseEntity<String> notify(
            @RequestHeader(value = "X-Notify-Key", required = false) String key) {

        if (!secretKey.equals(key)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        useCase.execute(LocalDate.now(ZoneId.of("America/Recife")));
        return ResponseEntity.ok("ok");
    }
}
```

---

## Etapa 6 — Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build:
```bash
mvn clean package -DskipTests
docker build -t notificador .
docker run -p 8080:8080 \
  -e GOOGLE_APPLICATION_CREDENTIALS=/app/service-account.json \
  -e TWILIO_ACCOUNT_SID=xxx \
  -e TWILIO_AUTH_TOKEN=xxx \
  -e TWILIO_FROM="whatsapp:+14155238886" \
  -e TWILIO_TO="whatsapp:+55819xxxxxxxx" \
  -e NOTIFY_SECRET_KEY=minha-chave-secreta \
  -v /caminho/local/service-account.json:/app/service-account.json \
  notificador
```

Testar localmente:
```bash
curl -X POST http://localhost:8080/notify -H "X-Notify-Key: minha-chave-secreta"
```

---

## Etapa 7 — Deploy Cloud Run

```bash
gcloud auth login
gcloud config set project rotina-semanal-af274
gcloud run deploy notificador \
  --source . \
  --region us-central1 \
  --platform managed \
  --allow-unauthenticated
```

Configurar env vars pelo Console GCP → Cloud Run → notificador → Editar → Variáveis.

---

## Etapa 8 — Cloud Scheduler

- **URL:** `https://<cloud-run-url>/notify`
- **Método:** POST
- **Header:** `X-Notify-Key: <sua-chave>`
- **Frequência:** `0 22 * * 1-5` (19h BRT = 22h UTC, seg a sex)
- **Fuso:** UTC

Testar manualmente pelo Console GCP antes de deixar agendado.

---

## Variáveis de ambiente necessárias

```
GOOGLE_APPLICATION_CREDENTIALS=/app/service-account.json
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=xxxxxxxxxxxx
TWILIO_FROM=whatsapp:+14155238886
TWILIO_TO=whatsapp:+5581xxxxxxxxx
NOTIFY_SECRET_KEY=escolha-uma-chave-forte
```
