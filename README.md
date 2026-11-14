# Monitoring System - SASPS

Fundație M1 pentru **Sistem de monitorizare resurse computaționale**:
server web Java monitorizat și serviciu de metrici în același JVM. Măsurăm
resursele procesului Java și ale mașinii, nu construim un produs de analiză HTTP.

## Pornire în 2 minute

Necesare: **JDK 17+** (`java`, `javac`, `jar`), Bash și, pentru demo, **Python 3.9+**.
Nu sunt necesare Maven, biblioteci Java, Docker, conturi sau chei.
Linux și macOS au colectare de rețea; alte sisteme raportează explicit lipsa ei.

```bash
bash scripts/build.sh
bash scripts/run.sh --port 8080 --interval-ms 1000 --interface auto
```

Într-un al doilea terminal:

```bash
curl -sS http://127.0.0.1:8080/health
curl -sS http://127.0.0.1:8080/metadata
curl -sS http://127.0.0.1:8080/metrics
curl -sS 'http://127.0.0.1:8080/work?iterations=500000'
curl -sS http://127.0.0.1:8080/history
```

Oprire: `Ctrl+C`. Serverul ascultă **numai pe `127.0.0.1`**. Dacă portul este ocupat,
folosiți `--port 8081` sau `--port 0` (port liber, afișat la pornire).
`--interface auto` alege alfabetic prima interfață activă non-loopback; nu presupune
că aceasta este interfața rutei implicite. Selectați explicit `--interface en0`
(exemplu macOS) sau `--interface eth0` (exemplu Linux) pentru un experiment real.
Pentru trafic HTTP local, folosiți `--interface lo0` pe macOS sau `--interface lo`
pe Linux; traficul loopback nu este traficul unei interfețe externe.

## Verificare și demonstrație reproductibilă

```bash
bash scripts/test.sh
python3 scripts/demo.py
```

Testele reconstruiesc aplicația și verifică parsarea contoarelor Linux/macOS,
Observer, retenția limitată, erorile și endpoint-urile HTTP. Mesajul
`SAMPLE_FAILED ... intentional test failure` este testul deliberat al recuperării
schedulerului, nu o eroare ascunsă a demo-ului.
Demo-ul pornește **propria instanță pe port liber**, așteaptă probe reale,
generează 6 secunde de trafic cu 4 clienți și închide instanța inclusiv la eroare.
Nu trebuie pornit manual un server înainte. Dovezile noi apar în
`build/demo-report.json` și `build/demo-server.log`. Aceste fișiere sunt locale,
ignorate de Git; păstrarea unei copii separate în repository a fost blocată de
gate-ul de permisiuni.

| Endpoint GET | Responsabilitate |
|---|---|
| `/health` | 503 înainte de prima probă/la eroare de eșantionare; 200 când serviciul funcționează |
| `/metadata` | Sursă, unitate, sens și domeniu pentru fiecare metrică; platformă și interfață |
| `/metrics` | Ultima probă reală, timestamp, interval efectiv și indisponibilități explicite |
| `/history` | Cel mult 60 de probe, de la cea mai veche la cea mai nouă |
| `/work?iterations=500000` | Sarcină CPU și alocare temporară de 256 KiB; 1..5.000.000 iterații |

`/work` este doar un generator limitat pentru demo. Nu simulează valori de metrici.
Metodele HTTP diferite de GET primesc 405; endpoint-urile necunoscute 404;
parametrii de workload invalizi 400. La indisponibilitatea unei surse, valoarea
este JSON `null` cu `unavailableReason`, **niciodată un zero inventat**.
`/health` nu garantează disponibilitatea fiecărei surse: aceasta se inspectează
în `/metrics`.

## Componente și stadiu

Codul este în `src/main/java/ro/sasps/monitoring/`.

| Componentă | Responsabilitate |
|---|---|
| `Main` | Compoziție explicită, configurare CLI, pornire/oprire |
| `MetricCollector`, `CpuCollector`, `MemoryCollector`, `GcCollector`, `NetworkCollector` | Strategie comună de colectare, o implementare per responsabilitate |
| `MetricService` | Un scheduler periodic și construirea probelor imuabile |
| `SampleBus` | Observer sincron: publică proba către consumator, fără buclă de polling |
| `SampleHistory` | Consumator sincron, istoric în memorie cu retenție de 60 de probe |
| `MonitorServer` | Server JDK HTTP; inspecție și sarcină demonstrativă |
| `Metric`, `Reading`, `Sample`, `Json` | Contract de date și serializare JSON restrânsă |

**Implementat M1:** colectare reală, interfață Strategy, Observer sincron, istoric
limitat, HTTP, teste și demo. Serverul și serviciul sunt componente distincte,
nu două deployment-uri; metricile de proces includ costul monitorului însuși.
**Planificat:** Abstract Factory + SPI registry; Object Pool + Flyweight peste
bounded Ring Buffer; variantele antipattern și experimentele comparative.
Un `ArrayDeque` cu retenție limitată **nu este prezentat drept Object Pool/Flyweight**.

Nu există alertare de producție, integrare Slack/email, dashboard, disc, senzori
suplimentari sau variante antipattern implementate.

- [Sursele și limitele metricilor](docs/METRICS.md)
- [Arhitectură și cele trei comparații exacte](docs/DESIGN.md)
- [Ipoteze și metodologie comparativă planificată](docs/EXPERIMENTS.md)
- [Prezentare M1 în română](presentation/M1-monitorizare-resurse.pptx)

Regenerarea prezentării folosește doar Python standard:

```bash
bash scripts/build.sh
python3 scripts/demo.py
python3 tools/generate_presentation.py
python3 tools/validate_presentation.py
```

Generatorul folosește numai rezultatele efectiv înregistrate în
`build/demo-report.json`, nu inventează rezultate și nu pretinde că
o regenerare reprezintă un experiment comparativ.

## Stadiul verificării și blocaje

Pe macOS, cu JDK 21.0.8, au trecut 47 de verificări Java și demonstrația cu
40.575 de cereri reale, 10 probe, 14/14 surse disponibile. În acea rulare scurtă:
CPU proces maxim 14,97% din capacitatea mașinii, delta RX loopback 46,07 MB,
759 colecții GC suplimentare, heap final 19,02 MiB. Sunt observații pe host
partajat, **nu** rezultate comparative sau garanții de performanță.

PPTX-ul cu 18 slide-uri a fost deschis de Microsoft PowerPoint și exportat în
PDF; toate paginile au fost randate și inspectate. Diacriticele și diagramele
sunt vizibile. Validatorul Python verifică doar structura, relațiile și limitele
formelor, nu înlocuiește această inspecție vizuală.

**Livrarea nu este încă M1 completă:** documentarea rolurilor propuse,
starter task-urilor individuale și planificarea sprinturilor au fost respinse
de gate-ul de permisiuni, inclusiv după confirmarea prin dialog. Slide-ul de
organizare marchează explicit acest blocaj. Linux are teste de parser pe
fixture-uri, nu verificare end-to-end în acest mediu; GC pause p99,
allocation rate și experimentele pattern–antipattern sunt lucru planificat.
