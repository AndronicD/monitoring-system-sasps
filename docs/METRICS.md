# Contractul metricilor M1

O probă este un instantaneu **aproximativ**, nu o citire atomică a tuturor surselor.
`timestamp` este începutul probei, în UTC; `sequence` crește la fiecare publicare.
`measurementDurationMs` măsoară colectarea înainte de publicare, nu latența până
la consumator. `intervalMs` este diferența dintre începuturile probelor consecutive,
calculată cu `System.nanoTime`; prima probă are interval 0.

Schedulerul folosește **fixed delay de 1.000 ms**, configurabil
`--interval-ms 100..60000`. Intervalul efectiv include durata colectării
(pe macOS și pornirea `netstat`) și jitterul de planificare. Toate metricile sunt
citite o dată per probă; ratele folosesc intervalul monotonic efectiv.
CPU host are fereastra internă a JDK, nu exact 1 secundă.

| ID | Sursă concretă | Unități | Domeniu și interpretare |
|---|---|---|---|
| `host.cpu.percent` | `OperatingSystemMXBean.getCpuLoad()` × 100 | % | Host vizibil JDK; sarcină recentă pe toate procesoarele logice |
| `process.cpu.percent` | Delta `getProcessCpuTime()` / delta timp / `availableProcessors()` × 100 | % capacitate mașină | Doar JVM monitorizat; un fir complet ocupat pe 8 procesoare ≈12,5%, nu 100% |
| `host.ram.total.bytes` | `getTotalMemorySize()` | bytes | RAM fizic vizibil OS/JDK |
| `host.ram.used.bytes` | total minus `getFreeMemorySize()` | bytes | Host; include cache-uri OS, nu RAM exclusiv al aplicațiilor |
| `jvm.heap.used.bytes` | `MemoryMXBean.getHeapMemoryUsage().getUsed()` | bytes | Proces; obiecte vii și încă necolectate, nu RSS |
| `jvm.heap.committed.bytes` | aceeași sursă, `getCommitted()` | bytes | Heap angajat de JVM |
| `jvm.heap.max.bytes` | aceeași sursă, `getMax()` | bytes | Limita heap, sau `null` dacă este nedefinită |
| `jvm.nonheap.used.bytes` | `MemoryMXBean.getNonHeapMemoryUsage().getUsed()` | bytes | Proces; nu include toate alocările native/stivele |
| `jvm.gc.collections.total` | Suma `GarbageCollectorMXBean.getCollectionCount()` | colecții cumulative | Proces, de la pornire; `null` dacă un GC bean nu oferă contor |
| `jvm.gc.collection.time.ms.total` | Suma `getCollectionTime()` | ms cumulativ | Timp aproximativ de colectare; **nu** distribuție de pauze STW/p99 |
| `host.network.rx.bytes.total` | Linux `/proc/net/dev`, coloana Receive bytes; macOS `netstat -ibn -I NAME`, `Ibytes` | bytes cumulativ | O singură interfață host, toate procesele, de la inițializarea contorului |
| `host.network.tx.bytes.total` | Aceleași surse, Transmit bytes / `Obytes` | bytes cumulativ | O singură interfață host, toate procesele |
| `host.network.rx.bytes.per.second` | Delta contor RX / delta timp efectiv | bytes/s | Primul delta și resetarea contorului sunt indisponibile |
| `host.network.tx.bytes.per.second` | Delta contor TX / delta timp efectiv | bytes/s | Nu reprezintă bandă rezervată și nu este per-proces |

## Limite explicite

- JDK 17+ OpenJDK/HotSpot sau echivalent cu `com.sun.management.OperatingSystemMXBean`.
  Un JDK fără această extensie eșuează la pornire cu motiv explicit.
- Linux și macOS sunt implementate pentru rețea. Windows/alte sisteme au `null`
  cu motiv pentru toate cele patru metrici de rețea și avertisment în stderr.
  Nu susținem că acele platforme au fost verificate cap-coadă.
- `--interface auto` nu identifică ruta implicită. Pentru comparare, fixați
  numele interfeței; o interfață inexistentă este eroare de configurare, nu fallback.
- macOS: folosim numai rândul `<Link#...>` pentru a nu dubla contoarele repetate
  în rândurile IPv4/IPv6. Procesul `netstat` are timeout de 750 ms; costul său este
  parte din costul monitorizării host, dar nu neapărat din timpul CPU al JVM.
- Linux: contoarele pot reflecta namespace-ul de rețea. În containere/cgroups,
  CPU/RAM JDK pot fi limitate de container, nu de mașina fizică. M1 se demonstrează
  nativ; nu combinăm aceste interpretări în același experiment.
- Nu există contor de rețea per-proces în API-ul JDK standard folosit. **Nu
  atribuim traficul host JVM-ului**. Nu măsurăm RSS; JVM heap + nonheap nu este RSS.
- Hostul este partajat. Alte procese pot afecta CPU/RAM/rețeaua. Loopback include
  traficul de inspecție și al altor aplicații locale; RX/TX nu se adună pentru
  a afirma trafic unic.
- Valorile negative/nesuportate sunt `null` cu motiv. Zero rămâne o măsurătoare
  validă când sursa raportează zero. Prima rată de rețea/CPU proces este `null`.
- Numărul și timpul cumulativ GC permit verificarea activității GC, **nu** calculul
  GC pause p99 sau allocation rate. Aceste axe necesită instrumentarea M2 descrisă
  în [EXPERIMENTS.md](EXPERIMENTS.md).

## Exemplu de formă JSON

Exemplu de **format**, nu rezultat experimental:

```json
{
  "sequence": 1,
  "timestamp": "2026-01-01T00:00:00Z",
  "intervalMs": 0.0,
  "measurementDurationMs": 2.0,
  "readings": {
    "process.cpu.percent": {
      "value": null,
      "unavailableReason": "First sample or process CPU time unsupported"
    }
  }
}
```
