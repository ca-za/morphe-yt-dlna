# Konzept: Sessions im WLAN (Fernsteuern und Mitmachen, wie Spotify Connect)

Status: Entwurf, nicht umgesetzt.

## Ziel

Jedes Handy mit der gepatchten YouTube- oder YouTube-Music-App im selben WLAN erkennt, dass
irgendwo gerade gecastet wird, und kann:

- **Fernsteuern:** Play/Pause, Spulen, nächster Titel, Lautstärke, auch aus der Benachrichtigung.
- **Mitmachen:** die Warteschlange sehen und bearbeiten (Titel anhängen, als Nächstes spielen,
  entfernen, umsortieren).
- **Mehrere Renderer** in einer Session bespielen, wie eine Sonos-Gruppe.

Die Erkennung soll auch im Hintergrund funktionieren, möglichst sogar ohne laufende App, mit
minimalem Akkuverbrauch.

## Was es schon gibt

| Baustein | Stand |
|---|---|
| Renderer-Suche (SSDP M-SEARCH) | vorhanden, beim Öffnen des Cast-Dialogs und in der App im Hintergrund |
| Renderer-Ereignisse (GENA-Abos) | vorhanden, im Rust-Proxy |
| HTTP-Server auf dem castenden Handy | vorhanden (Stream-Proxy, Zusatzdaten) |
| „Der Renderer führt“ | vorhanden: Das Handy folgt dem Zustand des Renderers |
| OpenHome-Playlist | vorhanden: Die Warteschlange liegt beim Renderer |
| Sich selbst im LAN ankündigen (SSDP-Responder oder DNS-SD) | fehlt |
| Steuer-API für andere Handys | fehlt |

Verworfen: YouTubes eigenes „Mit TV-Code verknüpfen“ (Lounge). Das ist genau diese Funktion, läuft
aber über Googles Server und verlangt, einen YouTube-TV-Client nachzuahmen. Das schließen wir aus.

## Grundidee: Der Renderer ist die gemeinsame Wahrheit

Wir haben die App bereits so gebaut, dass der Renderer den Zustand vorgibt. Das passt hier
perfekt: Mehrere Handys sind gleichberechtigte Steuergeräte desselben Renderers, und jedes folgt
seinem Zustand.

- **OpenHome-Renderer** (wie dein Renderer) halten Warteschlange, Position und Zustand selbst,
  und jedes Steuergerät darf sie lesen und ändern. Ein zweites Handy findet den Renderer, sieht in
  den Metadaten unsere `ytdlna-<videoId>`-Einträge und kann sofort mitsteuern. Das funktioniert
  sogar mit fremden Steuer-Apps wie BubbleUPnP oder Linn Kazoo.
- **Reine AVTransport-Renderer** kennen nur „aktueller und nächster Titel“. Eine Warteschlange
  gibt es dort nur auf dem Handy, das castet. Für diese Renderer braucht es einen
  **Session-Host** (siehe unten).

Deshalb zwei Ebenen:

1. **Renderer-Ebene (ohne Host):** Fernsteuern und Warteschlange direkt über OpenHome. Kein neues
   Protokoll nötig.
2. **Session-Ebene (mit Host):** Das castende Handy kündigt eine Session an und bietet eine kleine
   Steuer-API an. Das wird nötig für AVTransport-Renderer, mehrere Renderer, Rechte und Anzeigen
   wie „hinzugefügt von …“.

## Die Session

```
Session
  id, name            "Wohnzimmer – Carlos' Pixel"
  host                das Handy, das die Session gestartet hat
  app                 youtube | music
  renderers[]         { Gerät, Rolle (leader|follower), Lautstärke }
  queue[]             { videoId, Titel, Länge, Cover, addedBy, servedBy }
  current             Index, Position (vom Leader-Renderer), Zustand
  participants[]      verbundene Handys mit Rechten
```

### Ankündigen und Finden

- Der Host kündigt die Session per **DNS-SD** an (`_ytdlna-session._tcp`, Android `NsdManager`).
  Im TXT-Eintrag stehen Name, App, Renderer, laufender Titel und Port. Zusätzlich gibt es einen
  **SSDP-Responder** für Gerätetyp `urn:ytdlna-org:device:CastSession:1`. Der Host ist während des
  Casts ohnehin wach (Vordergrund-Benachrichtigung), das kostet also kaum zusätzlich.
- Andere Handys finden Sessions:
  - über dieselbe SSDP-Suche, die wir für Renderer schon machen; das kostet nichts extra,
  - und über DNS-SD, wenn die App offen ist.
- **Ohne Host:** Ein OpenHome-Renderer, dessen aktueller Titel unsere `urn:ytdlna-org`-Metadaten
  trägt, gilt als „Session ohne Host“. Fernsteuern und die Warteschlange gehen dann direkt über
  OpenHome.

### Steuer-API (auf dem Host, über den vorhandenen Proxy)

| Methode | Zweck |
|---|---|
| `GET /session` | Zustand als JSON, mit `ETag` |
| `GET /session?wait=25` | Long-Polling: antwortet sofort bei Änderungen, sonst nach 25 s |
| `POST /session/command` | `play`, `pause`, `seek`, `next`, `previous`, `volume` (je Renderer oder Gruppe) |
| `POST /session/queue` | `append`, `playNext`, `remove`, `move` |
| `POST /session/join` | Teilnahme anfragen; liefert ein Token |

Befehle setzt der Host um, wie heute Nutzerbefehle: an den Leader-Renderer, die Follower folgen.
Long-Polling statt GENA, weil es auf der Client-Seite einfacher und für den Akku besser ist: Nur
solange die Fernbedienung offen ist, steht eine Verbindung.

### Wer liefert den Stream eines hinzugefügten Titels?

Die Streams eines Titels kennt nur ein Handy, dessen YouTube-App den Titel geladen hat. Für ein
Handy, das einen Titel anhängt, gibt es zwei Varianten:

- **A, der Hinzufügende liefert:** Das hinzufügende Handy lädt den Titel in seiner App (ohne ihn
  abzuspielen, wie die heutige Vorschau), registriert ihn in seinem Proxy, und der Eintrag in der
  Warteschlange zeigt auf sein Handy (`servedBy`). Das ist einfach, aber wenn dieses Handy das WLAN
  verlässt oder schläft, fällt der Titel aus.
- **B, der Host übernimmt:** Erreicht die Warteschlange den Titel, lädt der Host ihn selbst. Wie
  heute folgt das Handy dem Renderer, und die Adresse `…/auto` wird dann vom Host bedient. Das ist
  robust, aber der Host muss den Titel in seiner App öffnen (Deep Link), auch wenn er nicht in
  seiner Warteschlange steht.

Vorschlag: A für die nächsten zwei Titel (sofort spielbar), B als Rückfall. Der heutige
Mechanismus „Renderer will einen Titel, Handy lädt ihn“ deckt B weitgehend schon ab.

### Rechte

Im WLAN ist nicht jeder vertrauenswürdig (Gäste-WLAN, Mitbewohner). Deshalb Stufen, einstellbar
am Host:

1. **Jeder im WLAN darf fernsteuern**, Warteschlange nur ansehen (Standard).
2. **Mitmachen nach Bestätigung:** Der Host bekommt „Lisas Pixel möchte mitmachen“ mit Erlauben
   oder Ablehnen; danach gibt es ein Token für die Session.
3. **Mitmachen ohne Nachfrage** (Party-Modus).

## Erkennung im Hintergrund, mit wenig Akku

Ein Handy, das nicht castet, soll Sessions bemerken und eine Benachrichtigung zeigen wie Spotify
(„Läuft auf Wohnzimmer TV – Fernbedienung“). Dauerhaftes Lauschen im Hintergrund (Multicast)
kostet zu viel Akku, und Android drosselt es ohnehin. Deshalb ereignisgesteuert:

| Auslöser | Was passiert | Kosten |
|---|---|---|
| App wird geöffnet, Cast-Knopf | SSDP- und DNS-SD-Suche, 2 bis 3 s | wie heute |
| Handy verbindet sich mit dem Heim-WLAN | `ConnectivityManager.registerNetworkCallback` mit `PendingIntent` weckt die App einmal, kurze Suche | ein kurzer Start pro WLAN-Verbindung |
| Optional: periodisch, nur im WLAN | `JobScheduler`, alle 15 bis 30 min (Android-Minimum 15), nur auf unbegrenztem Netz, ohne Wakelock | etwa 1 s Arbeit pro Lauf |
| Schnelleinstellungs-Kachel „YT DLNA“ | Suche beim Antippen, zeigt Sessions | nur bei Nutzung |

- Der periodische Job und die Benachrichtigung sind abschaltbar (Standard: aus); der WLAN-Auslöser
  ist an.
- **„Auch wenn die App nicht läuft“:** Das geht über `JobScheduler` und den Netzwerk-`PendingIntent`.
  Beide starten den App-Prozess kurz. Unser Patch kann dafür einen eigenen `JobService` und ein
  eigenes Kachel-Service im Manifest ergänzen. **Risiko:** Jeder Start lädt die
  `Application`-Klasse von YouTube mit. Läuft unser Job in einem eigenen Prozess
  (`android:process=":ytdlna"`), lädt Android dort zwar ebenfalls YouTubes `Application`; YouTube
  initialisiert in Nebenprozessen aber meist wenig. Das muss gemessen werden, bevor der
  periodische Job standardmäßig angeht.
- **Ist eine Session gefunden,** zeigt das Handy eine Benachrichtigung mit Medien-Steuerung (eine
  eigene `MediaSession`), solange die Session lebt. Abgefragt wird dann per Long-Polling nur
  während eine Ansicht offen ist, sonst alle 60 s. Endet die Session, verschwindet die
  Benachrichtigung.

## Mehrere Renderer (wie eine Sonos-Gruppe)

DLNA kennt keine synchronen Gruppen. Drei Fälle:

1. **Die Geräte können es selbst:**
   - **Sonos:** Gruppen über die eigene UPnP-Erweiterung (`x-rincon:<Koordinator>`). Wir spielen
     nur auf den Koordinator, Sonos synchronisiert sauber.
   - **OpenHome Songcast** (Sender und Receiver, etwa bei Linn): Ein Renderer sendet, die anderen
     empfangen synchron. Dein Renderer könnte das umsetzen.
2. **Raum-zu-Raum („Party“), ohne Gerätehilfe:** Ein Renderer ist Leader und gibt die Uhr vor.
   Die anderen bekommen denselben Titel. Der Host vergleicht die Positionen und korrigiert Follower
   per Seek, wenn sie mehr als etwa 1 s abweichen. Für verschiedene Räume reicht das; im selben Raum
   hört man den Versatz.
3. **Getrennt:** mehrere Renderer mit eigener Warteschlange in derselben Session (wie
   „Wohnzimmer“ und „Küche“ in einer App). Das ist im Grunde mehrere Sessions mit einer Oberfläche.

Die Session führt die Renderer mit ihrer Rolle. Lautstärke gibt es je Renderer und für die Gruppe
(relativ, wie bei Sonos). Fällt der Leader aus, wird der nächste Follower Leader.

## Oberfläche

- **Cast-Dialog:** Oben stehen „Aktive Sessions“ (Name, Renderer, Titel, Cover), darunter wie bisher
  die Geräte. Ein Tipp auf eine Session öffnet die Fernbedienung.
- **Fernbedienung:** ein eigenes Blatt im Spotify-Stil (Cover, Titel, Steuerung, Lautstärke je
  Renderer, Warteschlange mit Ziehen zum Umsortieren).
- **Titel teilen:** Im Menü eines Videos stehen „In Session anhängen“ und „Als Nächstes spielen“,
  auch über das Teilen-Menü (der Weg existiert schon für DLNA).
- **Benachrichtigung** auf den anderen Handys, siehe oben.

## Umsetzung in Stufen

1. **Sessions ohne Host anzeigen und fernsteuern:** OpenHome-Renderer mit unseren Metadaten werden
   im Dialog als „läuft gerade“ gezeigt; Fernbedienung mit Play/Pause/Spulen/Weiter/Lautstärke und
   Warteschlange ansehen. Nur Renderer-Ebene, kein neues Protokoll. Klein bis mittel.
2. **Warteschlange bearbeiten (OpenHome)** mit Variante A und B für die Streams. Mittel.
3. **Session-Host:** Ankündigung (DNS-SD und SSDP), Steuer-API, Rechte. Damit auch
   AVTransport-Renderer. Mittel bis groß.
4. **Hintergrund:** WLAN-Auslöser, Kachel, Benachrichtigung; der periodische Job erst nach Messung.
   Mittel.
5. **Mehrere Renderer:** Sonos-Gruppen, Party-Sync, Songcast. Groß, am besten zusammen mit deinem
   Renderer.

Stufe 1 bringt mit wenig Aufwand schon das wichtigste Erlebnis, „jemand castet, ich kann
mitsteuern“, und baut direkt auf dem auf, was es schon gibt.

## Offene Entscheidungen

- **Rechte-Standard:** Darf jeder im WLAN fernsteuern, oder nur nach Bestätigung?
- **YouTube und Music gemischt:** Darf ein YouTube-Handy eine Music-Session fernsteuern und Titel
  anhängen? Technisch ja; die Musik-Logik (Song- und Video-Modus) gilt dann nur für Music-Titel.
- **Hintergrund-Job:** Erst nach einer Messung des Akkuverbrauchs standardmäßig an?
