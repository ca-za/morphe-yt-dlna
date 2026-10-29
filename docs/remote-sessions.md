# Konzept: Sessions im WLAN (Fernsteuern und Mitmachen, wie Spotify Connect)

Status: teilweise umgesetzt. Erkennung im Hintergrund mit Benachrichtigung und Fernsteuerung
(Media Session) gibt es im Patch „DLNA session notifications“, Sonos-Gruppen und Räume im Patch
„DLNA Sonos rooms“ (siehe die Abschnitte unten). Offen sind Session-Host, Mitmachen und das
Bearbeiten der Warteschlange.

## Ziel

Jedes Handy mit der gepatchten YouTube-Music-App im selben WLAN erkennt, dass irgendwo gerade
gecastet wird, und kann:

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

Jeder im WLAN darf fernsteuern und die Warteschlange bearbeiten (siehe „Entscheidungen“).

## Erkennung im Hintergrund, mit wenig Akku

Status: umgesetzt als eigener, abschaltbarer Patch „DLNA session notifications“ (nur YouTube
Music, Standard: aus; das Aktivieren des Patches ist der Schalter).

Ein Handy, das nicht castet, soll Sessions bemerken und eine Benachrichtigung zeigen wie Spotify
(„Läuft auf Wohnzimmer TV“). Ein dauerhaft laufender Dienst, der im WLAN lauscht, kostet zu viel
Akku: Android verlangt dafür eine ständige Benachrichtigung, das WLAN-Modul müsste Multicast
dauernd annehmen, und im Tiefschlaf (Doze) sind Hintergrund-Apps ohnehin vom Netz getrennt.
Deshalb ereignisgesteuert, in einem eigenen leichten Prozess:

| Auslöser | Was passiert |
|---|---|
| App-Start | Der periodische Job wird geplant und der WLAN-Auslöser angemeldet (bleibt bis zu einem erzwungenen Beenden der App) |
| Neustart des Handys, App-Update | Ein Empfänger für `BOOT_COMPLETED` und `MY_PACKAGE_REPLACED` (im Prozess `:ytdlna`, Berechtigung `RECEIVE_BOOT_COMPLETED`) plant den Job neu und meldet den WLAN-Auslöser wieder an, ohne dass YouTube Music geöffnet wird |
| Handy verbindet sich mit einem unbegrenzten WLAN | `ConnectivityManager.registerNetworkCallback` mit `PendingIntent` weckt einen Empfänger, der einen einmaligen Job plant (5 s Wartezeit, damit das Netz steht) |
| Periodisch | `JobScheduler`, alle 20 min (Spielraum 10 min), nur auf unbegrenztem Netz, ohne Wakelock; im Tiefschlaf verschiebt Android ihn ins nächste Wartungsfenster |

- **Eigener Prozess `:ytdlna`:** Job und Empfänger laufen dort. Der Patch ändert die
  Basisklasse der `Application` von YouTube Music so, dass in diesem Prozess `attachBaseContext`
  nur den Kontext setzt und `onCreate` sofort zurückkehrt. Dort läuft also nichts von YouTube Music
  (keine Anmeldung, keine Bibliotheken), nur unser Code; Morphes eigener Einstiegs-Hook setzt
  dabei lediglich seinen Kontext.
- **Ein Lauf:** SSDP-Suche nach Renderern (2,5 s, Multicast-Lock nur währenddessen), dazu die per
  Adresse hinzugefügten Geräte. Ein Renderer, dessen aktueller Titel unsere Metadaten trägt
  (Eintrag `ytdlna-…` oder `urn:ytdlna-org`) und der spielt oder pausiert, ist eine Session.
  Gefunden: Benachrichtigung „Läuft auf <Renderer>“ mit dem Titel (Kanal „DLNA-Sessions“, leise),
  ein Tipp öffnet YouTube Music. Keine Session: die Benachrichtigung verschwindet. Castet dieses
  Handy selbst, wird nichts angezeigt.
- **Gemessen im Emulator:** ein Lauf etwa 2,5 s (die Suche), 10 bis 40 ms CPU-Zeit für die Suche,
  rund 70 ms CPU-Zeit für den ganzen Prozess inklusive Start. Bei drei Läufen pro Stunde sind
  das wenige Sekunden CPU-Zeit am Tag.
- **Nicht umgesetzt:** Kachel in den Schnelleinstellungen.

### Fernsteuerung (Media Session)

Die Benachrichtigung hat Knöpfe: Pause/Abspielen, Weiter (Renderer mit OpenHome-Playlist) und
„Steuern“. Ein Tipp startet die Fernsteuerung: eine Android Media Session wie bei Spotify Connect,
mit Titel, Cover, Position, Play/Pause, Spulen, Weiter/Zurück und der Lautstärke des Renderers auf
den Lautstärketasten, in der Benachrichtigung und auf dem Sperrbildschirm.

- **Warum erst nach einem Tipp:** Android lässt einen Hintergrund-Job keinen Vordergrunddienst
  starten, wohl aber einen Tipp auf eine Benachrichtigung. Die Media Session braucht den
  Vordergrunddienst (Typ `mediaPlayback`), sonst beendet Android ihren Prozess und die Knöpfe auf
  dem Sperrbildschirm wären tot.
- **Kein Audio-Fokus:** Die Session spielt selbst nichts ab und fordert keinen Audio-Fokus an. Sie
  pausiert also nichts und wird von nichts pausiert. Spielt das Handy selbst etwas ab (jede App,
  `AudioManager.isMusicActive`, gemeldet über `AudioPlaybackCallback`), gibt sie sich frei: Player
  und Benachrichtigung verschwinden, der Renderer spielt weiter. Befehle gehen nur auf einen Tipp
  des Nutzers an den Renderer.
- **Akku:** Der Dienst liest den Renderer alle 5 s, solange der Bildschirm an ist, und gar nicht,
  solange er aus ist (beim Einschalten sofort wieder). Er endet, wenn der Renderer stoppt, der
  Titel nicht mehr von uns ist, das Handy selbst castet oder abspielt, oder mit „Schließen“.
- **Wegwischen:** Eine weggewischte Benachrichtigung bleibt für diesen Titel weg.
- **Fremd oder eigen:** Zeigt die Stream-Adresse auf dieses Handy, ist es kein fremder Cast (keine
  Benachrichtigung).

## Mehrere Renderer (wie eine Sonos-Gruppe)

DLNA kennt keine synchronen Gruppen. Drei Fälle:

1. **Die Geräte können es selbst:**
   - **Sonos (umgesetzt):** Gruppen über die eigene UPnP-Erweiterung. Die Geräteliste liest die
     Gruppen (`ZoneGroupTopology#GetZoneGroupState`) und zeigt je Gruppe eine Zeile mit dem Raum,
     der sie führt („Küche + 1“); Gruppenmitglieder und versteckte Lautsprecher (zweiter
     Lautsprecher eines Stereopaars, Sub, Surround) fallen weg. Gecastet wird auf diesen
     Koordinator, Sonos spielt die anderen Räume synchron mit. Der Räume-Knopf der Zeile öffnet
     einen Dialog wie in der Sonos-App: Räume hinzufügen (`SetAVTransportURI` mit
     `x-rincon:<Koordinator>` auf dem Raum) oder entfernen (`BecomeCoordinatorOfStandaloneGroup`),
     dazu die Lautstärke je Raum. Die Lautstärketasten regeln bei einer Gruppe die
     Gruppenlautstärke (`GroupRenderingControl`, die Räume behalten ihr Verhältnis). Angezeigt
     wird der Raumname (`roomName` der Gerätebeschreibung).
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
4. **Hintergrund:** WLAN-Auslöser, Benachrichtigung, periodischer Job, Fernsteuerung.
   **Umgesetzt** (ohne Kachel).
5. **Mehrere Renderer:** Sonos-Gruppen, Party-Sync, Songcast. Groß, am besten zusammen mit deinem
   Renderer.

Stufe 1 bringt mit wenig Aufwand schon das wichtigste Erlebnis, „jemand castet, ich kann
mitsteuern“, und baut direkt auf dem auf, was es schon gibt.

## Entscheidungen

- **Rechte:** Jeder im WLAN darf fernsteuern und mitmachen. Abschotten ließe sich das bei DLNA
  ohnehin kaum: Jede DLNA-Steuer-App kann die Renderer direkt bedienen. Die Bestätigungsstufe
  entfällt; ein Token bleibt nur, um Teilnehmer zu unterscheiden („hinzugefügt von …“).
- **Nur YouTube Music:** Sessions gibt es nur in der Music-App. Die YouTube-App bekommt die
  Funktion nicht und zeigt auch keine Music-Sessions an.
- **Hintergrund-Job:** eigener leichter Prozess, umgesetzt (siehe „Erkennung im Hintergrund“).
