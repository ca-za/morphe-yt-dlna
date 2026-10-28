# Konzept: SponsorBlock, Untertitel und Hinweise für den Renderer

Status: Stufe 1 (Extras-Dokument mit SponsorBlock-Abschnitten) und Stufe 2 (Untertitel) sind
umgesetzt; Abweichungen vom Entwurf stehen unter „Umgesetzt“ am Ende. Stufen 3 und 4 sind offen.

Ziel: Ein Renderer soll SponsorBlock-Abschnitte, Untertitel und Texthinweise (etwa „Sponsor
übersprungen“) kennen und selbst berücksichtigen können. Jeder andere DLNA/UPnP-Renderer und
jeder andere Control Point muss dabei genau so funktionieren wie heute.

## Grundsätze

1. **Nur Standard-Erweiterungspunkte.** Alles läuft über Stellen, die UPnP AV, DLNA und
   OpenHome für Erweiterungen vorsehen und die Unkundige ignorieren müssen: zusätzliche
   `res`-Elemente und `<desc>`-Blöcke in DIDL-Lite, zusätzliche Formate in
   `ConnectionManager.GetProtocolInfo`, herstellereigene Dienste in der Gerätebeschreibung.
2. **Erst fragen, dann senden.** Zusätzliches geht nur an Renderer, die es ankündigen.
   Unbekannte Renderer bekommen exakt die heutigen Metadaten.
3. **Metadaten klein halten.** In DIDL-Lite stehen nur Verweise (URLs), keine Listen. Manche
   Renderer kürzen oder verwerfen lange Metadaten.
4. **Ziehen statt schieben.** Die Daten liefert der Stream-Proxy auf dem Handy über HTTP, an
   der Stelle, an der der Renderer heute schon den Stream holt. Das löst auch das Zeitproblem:
   Morphe lädt die SponsorBlock-Abschnitte erst 1–2 s nach dem Videostart, dann ist der Titel
   schon beim Renderer, und Metadaten lassen sich ohne Neuladen nicht ändern.
5. **Wer überspringt, ist eindeutig.** Entweder der Renderer (wenn er es ankündigt) oder wie
   heute das Handy per Seek. Nie beide.

## 1. Fähigkeiten des Renderers erkennen

Ein Renderer meldet, was er kann, in `GetProtocolInfo` (Sink-Liste). Diese Liste lesen wir
heute schon beim Verbindungsaufbau (`DlnaController.loadSinkProtocols`).

| Eintrag in der Sink-Liste | Bedeutung |
|---|---|
| `http-get:*:text/srt:*` | kann SRT-Untertitel als zusätzliche Ressource |
| `http-get:*:text/vtt:*` | kann WebVTT-Untertitel |
| `http-get:*:application/vnd.ytdlna.extras+json:*` | versteht unser Extras-Dokument (Abschnitte, Kapitel, Hinweise) |

Das ist reiner Standard: Ein Renderer listet dort die Formate, die er annimmt. Andere Control
Points sehen nur ein weiteres Format, das sie nicht nutzen.

Optional (Stufe 3) kündigt der Renderer zusätzlich einen herstellereigenen Dienst in seiner
`description.xml` an, über den er Befehle annimmt:

```xml
<service>
  <serviceType>urn:ytdlna-org:service:CastExtras:1</serviceType>
  <serviceId>urn:ytdlna-org:serviceId:CastExtras</serviceId>
  <SCPDURL>/CastExtras.xml</SCPDURL>
  <controlURL>/CastExtras/control</controlURL>
  <eventSubURL>/CastExtras/event</eventSubURL>
</service>
```

UPnP erlaubt herstellereigene Dienste ausdrücklich; Control Points ohne Kenntnis ignorieren sie.

Bei Samsung-Geräten (Hersteller in der Gerätebeschreibung) kommt für Untertitel zusätzlich deren
eigenes Verfahren dazu, siehe Abschnitt 3.

## 2. Das Extras-Dokument

Der Stream-Proxy liefert pro Titel ein JSON-Dokument:

`GET http://<handy>:<port>/extras/<videoId>.json`

```json
{
  "version": 1,
  "videoId": "3LopI4YeC4I",
  "duration": 723.4,
  "complete": true,
  "segments": [
    {"category": "sponsor", "start": 657.86, "end": 722.18, "action": "skip-once"},
    {"category": "selfpromo", "start": 658.20, "end": 723.74, "action": "manual"},
    {"category": "poi_highlight", "start": 95.0, "action": "jump"}
  ],
  "chapters": [
    {"title": "Einleitung", "start": 0.0},
    {"title": "Test", "start": 61.5}
  ],
  "captions": [
    {"language": "de", "label": "Deutsch", "auto": false, "default": true,
     "srt": "http://<handy>:<port>/captions/3LopI4YeC4I.de.srt",
     "vtt": "http://<handy>:<port>/captions/3LopI4YeC4I.de.vtt"}
  ],
  "skipBy": "phone"
}
```

- **`segments`:** Morphes Abschnitte mit den Einstellungen des Nutzers je Kategorie. Morphes
  Verhalten wird übersetzt:

  | Morphe | `action` | Renderer tut |
  |---|---|---|
  | automatisch überspringen | `skip` | springt ans Ende |
  | einmal automatisch überspringen | `skip-once` | springt beim ersten Mal |
  | Knopf zeigen | `manual` | zeigt „Überspringen“ an |
  | nur in Leiste zeigen | `show` | markiert den Abschnitt in seiner Zeitleiste |
  | ignorieren | (nicht enthalten) | |

  Der Highlight-Abschnitt (`poi_highlight`) hat nur einen Startpunkt; der Renderer kann
  „Zum Highlight springen“ anbieten.
- **`complete`:** `false`, solange Morphe noch lädt. Der Renderer holt das Dokument dann erneut.
  Das kann er bequem mit `If-None-Match` (der Proxy setzt einen `ETag`) oder mit Long-Polling:
  `?wait=10` hält die Anfrage offen, bis sich etwas ändert, höchstens 10 s.
- **`skipBy`:** Wer überspringt, siehe Abschnitt 4.
- **Zeiten** in Sekunden des Videos, so wie sie die AVTransport-Position meldet.

Gefunden wird das Dokument über einen `<desc>`-Block in den Metadaten (`SetAVTransportURI`,
OpenHome `Playlist.Insert`). DIDL-Lite sieht `<desc>` genau für herstellereigene Zusatzdaten vor:

```xml
<item id="..." parentID="..." restricted="1">
  <dc:title>…</dc:title>
  <upnp:class>object.item.videoItem</upnp:class>
  <res protocolInfo="http-get:*:video/mp4:DLNA.ORG_OP=01">http://<handy>:<port>/stream/…</res>
  <desc id="ytdlna" nameSpace="urn:ytdlna-org:metadata-1">
    <ytdlna:extras xmlns:ytdlna="urn:ytdlna-org:metadata-1" version="1"
        videoId="3LopI4YeC4I" href="http://<handy>:<port>/extras/3LopI4YeC4I.json"/>
  </desc>
</item>
```

Der `<desc>`-Block kommt nur in die Metadaten, wenn der Renderer
`application/vnd.ytdlna.extras+json` angekündigt hat. Bei OpenHome reicht der Info-Dienst die
Metadaten an andere Control Points weiter; ein zweites Steuergerät findet so das Dokument auch.

## 3. Untertitel

Untertitel sind der einzige Teil, für den es verbreitete (wenn auch nicht genormte) Verfahren
gibt. Wir bedienen die drei üblichen, jeweils nur bei passender Ankündigung:

1. **Zusätzliches `res`** mit `text/srt` (oder `text/vtt`), wenn die Sink-Liste das Format
   enthält. Viele Renderer (Kodi, LG, BubbleUPnP-Renderer und andere) werten das aus. Die
   Video-Ressource bleibt das erste `res`; Renderer, die nur das erste lesen, ändert das nicht.
   ```xml
   <res protocolInfo="http-get:*:text/srt:*">http://<handy>:<port>/captions/ID.de.srt</res>
   ```
2. **Samsung:** `<sec:CaptionInfoEx sec:type="srt">URL</sec:CaptionInfoEx>` im Item
   (Namespace `xmlns:sec="http://www.sec.co.kr/"`), und der Stream-Proxy antwortet auf eine
   Stream-Anfrage mit dem Header `getCaptionInfo.sec: 1` mit `CaptionInfo.sec: URL`.
3. **Unser Extras-Dokument** listet alle Sprachen; ein Renderer mit Extras-Unterstützung bietet
   die Auswahl selbst an.

Herkunft: Der Load-Hook (`VideoLoadFingerprint`) bekommt die Player-Antwort, in der die
Untertitel-Spuren stehen. Abgerufen werden sie über die URLs, die auch die App nutzt;
wenn YouTube dafür ein Token verlangt, lesen wir stattdessen die Untertitel, die die App selbst
geladen hat (Hook an ihrem Untertitel-Parser). Wir fälschen dafür keinen Client und erzeugen
kein Token. Der Proxy wandelt in SRT und WebVTT um.

Welche Spur: die, die der Nutzer am Handy gewählt hat; sind Untertitel am Handy aus, wird
keine Untertitel-Ressource gesendet (der Renderer kann sie über das Extras-Dokument trotzdem
anbieten).

## 4. Wer überspringt

| Renderer kündigt an | `skipBy` | Ablauf |
|---|---|---|
| nichts | – | wie heute: Morphe überspringt am Handy, der Seek geht als Befehl an den Renderer |
| nur Extras | `phone` | wie oben; der Renderer zeigt die Abschnitte nur an (Zeitleiste, „Sponsor“-Hinweis) |
| Extras und Dienst `CastExtras` mit `SetSkipping(true)` | `renderer` | der Renderer überspringt selbst nach seiner Position; das Handy folgt dem Renderer und leitet Morphes Sprünge nicht mehr weiter |

Beim Überspringen durch den Renderer gibt es keinen Versatz mehr zwischen Handy und Fernseher:
Der Sprung passiert an der Position, die man sieht. Das passt zum Grundsatz „der Renderer
führt“: Er springt, meldet die neue Position, und das Handy folgt.

Die Übergabe ist ausdrücklich: Erst wenn der Renderer `SetSkipping(true)` bestätigt, setzt das
Handy `skipBy` auf `renderer` und hört auf, Morphes Sprünge zu senden. Ohne Bestätigung bleibt
alles beim Handy; ein Fehler kann also nicht dazu führen, dass niemand überspringt.

## 5. Texthinweise

Hinweise wie „Sponsor übersprungen (1:04)“, „Kapitel: Test“ oder „Weiter auf dem Handy“.

- **Jeder Renderer mit Untertiteln:** Hinweise werden als kurze Einträge in die SRT-Spur
  eingefügt, die wir ohnehin liefern (2–3 s lang, an der Stelle des Ereignisses). Das ist voll
  kompatibel: Für den Renderer ist es ein gewöhnlicher Untertitel. Nur möglich, solange der
  Renderer die Spur noch nicht vollständig geladen hat; daher vor allem für vorhersehbare
  Hinweise (Abschnitte, Kapitel). Abschaltbar.
- **Renderer mit Extras-Unterstützung:** Hinweise kommen als Ereignisse über den Dienst
  `CastExtras` (GENA-Event `Notice`) oder, ohne Dienst, über
  `GET /extras/<videoId>/notices?since=<n>` (Long-Polling). Der Renderer zeigt sie in seiner
  eigenen Oberfläche, etwa als Einblendung.

## 6. Der Dienst `CastExtras` (Stufe 3)

Aktionen, die ein Renderer anbieten kann:

| Aktion | Argumente | Zweck |
|---|---|---|
| `GetExtrasVersion` | → `Version` | Version des Formats, das er versteht |
| `SetSkipping` | `Enabled` (bool) → `Enabled` | Übergabe des Überspringens; Antwort bestätigt |
| `SkipSegment` | – | „Überspringen“ für einen `manual`-Abschnitt vom Handy aus |

Zustandsvariablen mit Ereignis: `Notice` (Text), `CurrentSegment` (Kategorie oder leer). Damit
kann umgekehrt das Handy anzeigen, dass der Fernseher gerade in einem Sponsor-Abschnitt ist.

## 7. Kompatibilität im Überblick

| Gegenüber | Was es sieht | Risiko |
|---|---|---|
| beliebiger DLNA-Renderer | identische Metadaten wie heute | keins |
| Renderer mit `text/srt` | ein zusätzliches `res` hinter dem Video | gering; abschaltbar je Gerät |
| Samsung | `sec:CaptionInfoEx` und Header nur auf Nachfrage | gering |
| unser Renderer | `<desc>` mit URL, Dienst `CastExtras` | – |
| andere Control Points (OpenHome) | `<desc>` in den Metadaten, das sie ignorieren | keins |

Absicherung: ein Schalter je Funktion in den Morphe-Einstellungen (Untertitel senden,
Extras senden, Hinweise in Untertiteln) und eine Liste von Geräten, bei denen ein zusätzliches
`res` Probleme macht (durch Tests ermittelt, nicht vorab).

## 8. Umsetzung in Stufen

1. **Extras-Dokument und `<desc>`** (nur bei Ankündigung). Inhalt: Abschnitte und Kapitel.
   Überspringen bleibt beim Handy. Wenig Aufwand, da Morphes Abschnitte schon gelesen werden
   (`SponsorBlock.java`).
2. **Untertitel:** Spuren aus der Player-Antwort, Umwandlung im Proxy, `res`-Eintrag,
   Samsung-Verfahren, Auswahl nach Handy-Einstellung. Mittlerer Aufwand; Risiko beim Abruf
   (Token), daher erst prüfen.
3. **Dienst `CastExtras`:** Übergabe des Überspringens, Hinweise als Ereignisse. Aufwand vor
   allem im Renderer.
4. **Hinweise in Untertiteln** für fremde Renderer, falls gewünscht.

Für Tests bekommt der Test-Renderer (AirPlay-Emulator) bzw. ein kleiner Mock die Ankündigungen,
und die Regressionstests prüfen, dass ein Renderer ohne Ankündigung byte-gleiche Metadaten
bekommt.

## Umgesetzt (Stand alpha.9)

- **Extras-Dokument** unter `/extras/<videoId>.json` mit `segments` (Morphes Abschnitte und die
  Einstellung je Kategorie) und `captions`; `chapters` ist noch leer. Der Proxy liefert Texte
  mit `ETag` und `Cache-Control: no-cache`, auf `If-None-Match` mit 304. Long-Polling (`?wait=`)
  gibt es nicht; der Renderer fragt erneut, solange `complete` `false` ist (spätestens 15 s nach
  dem Laden ist es `true`).
- **Titel der Warteschlange** (OpenHome-Playlist, `SetNextAVTransportURI`) tragen den Verweis
  schon; ihr Dokument gibt es erst, wenn das Handy den Titel spielt. Bis dahin antwortet der
  Proxy mit 404.
- **Untertitel:** Die Spuren kommen aus der Player-Antwort der App (dieselben Adressen, die die
  App nutzt; ein Token ist dafür derzeit nicht nötig). Sie werden in SRT und WebVTT umgewandelt:
  `/captions/<videoId>.<sprache>[-auto].srt|vtt`.
  - Mit dem Titel geht die Spur in der Sprache des Handys (geschriebene vor automatisch erzeugter)
    als zusätzliches `res` mit `text/srt` (oder `text/vtt`, wenn der Renderer nur das listet).
  - Samsung-Geräte (Hersteller in der Gerätebeschreibung) bekommen zusätzlich
    `sec:CaptionInfoEx`. Der HTTP-Header `CaptionInfo.sec` ist noch nicht umgesetzt.
  - Alle Spuren stehen nach und nach im Extras-Dokument (im Hintergrund geladen).
  - Schalter „DLNA: Untertitel“ in den Morphe-Einstellungen (Standard: an). Ob am Handy
    Untertitel eingeschaltet sind, wird noch nicht berücksichtigt.
- **Ankündigung:** nur ausdrücklich gelistete Formate zählen (`*:*` nicht).
- **Tests:** `renderer_extras` prüft, dass ein Renderer ohne Ankündigung nichts davon bekommt,
  und mit Ankündigung Dokument, Abschnitte, Untertitel-Ressource (gültiges SRT) und ETag.
