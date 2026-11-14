# Ipoteze și protocol comparativ — PLANIFICAT

**Nu există rezultate pattern–antipattern la M1.** Demo-ul păstrat verifică numai
fundația cu citiri reale; nu susține avantaje de performanță sau mentenabilitate.
Cele trei comparații exacte sunt în [DESIGN.md](DESIGN.md).

## Condiții comune și reproductibilitate

1. Fixați commit-ul, OS/JDK, GC, procesoarele disponibile, `-Xms/-Xmx`, interfața
   rețelei, setul de metrici, serializerul, numărul consumatorilor și workload-ul.
   Folosiți același host nativ și opriți sarcinile de fundal controlabile.
2. Schimbați **un singur punct de variație** A/B/C per experiment; restul
   componentelor rămân identice. Rularea tuturor schimbărilor simultan nu izolează
   cauzalitatea.
3. Protocol propus, de fixat înaintea implementării: warm-up 30 s, măsurare
   minimum 120 s, minimum 5 repetări per variantă, ordine alternată/randomizată
   cu seed 42. Exportați probele brute și configurația, nu doar mediile.
4. Scenarii egale: idle fără `/work`; nominal 20 cereri/s; high-frequency
   200 cereri/s; burst 200 cereri/s timp de 5 s la fiecare 20 s.
   Aceste rate sunt **ținte propuse**, nu măsurători M1. Un generator open-loop
   va înregistra ratele efective, erorile și backlog-ul; dacă nu le atinge,
   raportează abaterea, nu reetichetează scenariul.
5. Se păstrează aceeași sursă periodică la 1.000 ms în pereche. Un scenariu
   suplimentar de stres al pipeline-ului poate reda aceleași probe înregistrate
   la 100/1.000/10.000 probe/s cu seed fix; nu se numesc citiri OS la acele rate.
   Nu confundăm rata cererilor HTTP cu rata probelor de resurse.
6. Percentile: nearest-rank `sorted[ceil(p*n)-1]`, cu `n`, unități și fereastră
   raportate. Pentru latență p99 urmărim cel puțin 10.000 evenimente/variantă
   folosind replay; la numai 120 probe de 1 Hz nu pretindem un p99 stabil.
   Pentru p99 GC puține pauze se raportează explicit „date insuficiente”.
7. Prezentați mediane și dispersia între repetări; excludeți warm-up și păstrați
   logurile eșecurilor. Host CPU/RAM/rețeaua includ alte procese. CPU-ul unui
   subproces `netstat` se evaluează separat sau se include în costul întregului
   proces-tree; nu atribuim automat totul JVM-ului.

## A — Observer / Pub-Sub (event bus, push) vs Busy Polling / Spin-Wait

**Ipoteză testabilă H-A:** push reduce CPU proces la idle față de spin-wait,
fără o regresie p99 measurement-to-consumer mai mare de 5 ms (marjă propusă).
Ipoteza poate fi infirmată; beneficiul nu este garantat.

- Același sampler și aceleași probe; un consumator, același predicat și același
  mecanism de validare a secvențelor, fără alertare/notificări.
- Măsurați CPU-time proces / timp wall și normalizare la procesoarele logice.
  Idle înseamnă fără HTTP workload, **nu fără eșantionare**.
- Marcați `t_source` înaintea colectării, `t_ready` după construirea probei,
  `t_consumer` la recepție și `t_detect` când harness-ul evaluează predicatul.
  Ceas comun `nanoTime` în același proces, nu comparație între ceasuri de mașini.
- Raportați separat `t_consumer - t_ready` (transport), `t_detect - t_source`
  (măsurare până la detecție) și, pentru semnal sintetic cunoscut,
  `t_detect - t_signal` (include așteptarea până la următorul sampling).
  Latența HTTP nu înlocuiește aceste măsurători.
- Spin fără `sleep` este varianta Busy Polling / Spin-Wait de bază. Dacă se
  adaugă și polling cu sleep, este o a treia variantă etichetată separat; nu
  ascundem întârzierea prin schimbarea intervalului de sampling.
- Raportați evenimente pierdute/duplicate și backpressure. Aceeași politică de
  livrare și capacitate în pereche; dacă polling-ul pierde evenimente față de
  push, perechea nu este echivalentă până la remediere.

## B — Strategy + Abstract Factory + SPI registry vs God Object (The Blob)

**H-B:** varianta modulară reduce complexitatea ciclomatică maximă/metodă și
cuplarea per componentă; o schimbare controlată afectează mai puține clase
existente și are efort mai mic decât Blob-ul.

- Aceleași 14 metrici, aceeași serializare și aceleași scenarii; testele de
  conformitate compară schema/valorile cu toleranțe și indisponibilități.
- Challenge fix propus: adăugarea unui backend OS alternativ pentru
  **contoarele de rețea existente**, cu fixture și eroare explicită, fără o
  categorie nouă de resurse. Comparați același acceptance test.
- Rulați aceeași versiune a unui analizor static (de selectat/înregistrat înainte
  de experiment); raportați CC/metodă (maxim și distribuție), CBO/clasă și
  dependențe între pachete, plus totalul proiectului. Nu este suficient „mai
  multe clase înseamnă mai bine”.
- Efort: minute până la teste verzi, linii și fișiere existente modificate,
  fișiere noi, număr de defecte introduse. Aceiași participanți cu ordine
  contrabalansată sau limitarea explicită a efectului de învățare.
- Măsurați costul Factory/SPI (configurare, clase suplimentare, erori de
  descoperire). Ipoteza poate eșua pentru o aplicație mică.

## C — Object Pool + Flyweight over bounded Ring Buffer vs Object Churn + Unbounded Collection

**H-C:** pool/flyweight/ring reduce bytes alocați/s și p99 al pauzelor GC,
menține un platou al heap-ului după warm-up și evită creșterea necontrolată
observată la colecția nelimitată în aceeași durată și cu același `-Xmx`.

- Aceleași probe și rată de intrare, aceleași metadate, consumatori, transformări
  și rezultat pentru fereastra comună de 60 probe. Exportați numărul suprascrierilor.
- Rulare **scurtă** înainte de saturare: comparație funcțională directă.
  Rulare **lungă**: ring-ul elimină vechi, colecția nelimitată păstrează tot;
  documentați diferența intenționată de retenție. Nu afirmați că o fereastră
  limitată oferă aceleași posibilități de istoric ca retenția nelimitată.
- Allocation rate: counter exact `com.sun.management.ThreadMXBean` pe firele
  cunoscute ale pipeline-ului, sau profiler echivalent validat; înregistrați
  domeniul firelor, bytes/s și bytes/probă. JFR allocation samples sunt estimări
  statistice și nu se prezintă drept total exact.
- GC pause p99: evenimente de pauză STW din JFR/GC log unificat, cu
  `-Xlog:gc*,safepoint:file=...:time,uptime,level,tags`; parserul trebuie să
  selecteze pauze individuale și să evite dublarea fazelor. Timpul GC cumulativ
  din M1 **nu se împarte la colecții pentru a inventa un p99**.
- Heap growth: serie temporală heap used și ocupare după GC, slope bytes/minut
  după warm-up; raportați și peak/committed/max. Evaluarea pooling-ului include
  retenția sloturilor și costul snapshot-urilor coerente pentru HTTP.
- OOM: numai într-un **proces copil izolat**, `-Xmx64m`, timeout 120 s propus,
  loguri capturate și fără heap dump pe host partajat. Limitați writerul
  harness-ului pentru a nu epuiza memoria mașinii. Raportați tipul OOM,
  timp/probe până la OOM sau „nu s-a observat în interval”; nu presupuneți că
  fiecare rulare nelimitată va ajunge obligatoriu la OOM.

## Ce există acum

`bash scripts/test.sh` și `python3 scripts/demo.py` verifică baseline-ul real.
Generatorul open-loop, replay-ul, variantele antipattern, registry-ul SPI,
pool-ul/ring-ul, parserul JFR/GC și analiza statică comparativă sunt **planificate**.
Nu există comenzi comparative fictive sau rezultate comparative în prezentare.
