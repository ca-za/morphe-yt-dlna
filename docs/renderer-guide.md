# Renderer-Anleitung: Zusatzdaten von YT DLNA nutzen

Für Entwickler von DLNA/UPnP- oder OpenHome-Renderern, die mehr aus einem Cast von YouTube oder
YouTube Music machen wollen: Untertitel, Lyrics, SponsorBlock-Abschnitte. Alles ist optional;
ein Renderer, der nichts davon ankündigt, bekommt genau die üblichen Metadaten.

Hintergrund und Entwurfsentscheidungen: [renderer-extras.md](renderer-extras.md).

## 1. Ankündigen

Der Renderer listet die Formate in `ConnectionManager.GetProtocolInfo` (Sink):

| Eintrag | Bekommt der Renderer |
|---|---|
| `http-get:*:application/vnd.ytdlna.extras+json:*` | das Zusatzdaten-Dokument (Abschnitte 3 bis 5) |
| `http-get:*:text/srt:*` | eine Untertitel-Datei als zusätzliches `res` (Abschnitt 2) |
| `http-get:*:text/vtt:*` | dasselbe als WebVTT, wenn `text/srt` fehlt |

Platzhalter wie `http-get:*:*:*` zählen nicht. Die App liest die Liste einmal pro Gerät und
Programmstart; nach einer Änderung am Renderer die App neu starten.

## 2. Metadaten eines Titels

In `CurrentURIMetaData` (AVTransport `SetAVTransportURI`, `SetNextAVTransportURI`) bzw. in den
Metadaten von OpenHome `Playlist.Insert` steht nach der Medien-Ressource:

```xml
<item id="ytdlna-3LopI4YeC4I" parentID="0" restricted="1">
  <dc:title>…</dc:title>
  <upnp:class>object.item.videoItem</upnp:class>
  <res protocolInfo="http-get:*:application/dash+xml:*">http://192.168.1.20:8720/v/3LopI4YeC4I/…</res>
  <res protocolInfo="http-get:*:text/srt:*">http://192.168.1.20:8720/captions/3LopI4YeC4I.en.srt</res>
  <desc id="ytdlna" nameSpace="urn:ytdlna-org:metadata-1">
    <ytdlna:extras xmlns:ytdlna="urn:ytdlna-org:metadata-1" version="1"
        videoId="3LopI4YeC4I" href="http://192.168.1.20:8720/extras/3LopI4YeC4I.json"/>
  </desc>
</item>
```

- Das erste `res` ist immer das Medium. Ein `res` mit `text/srt` oder `text/vtt` ist die
  Untertitel-Spur, die zum Titel gehört: in der Sprache des Handys, in YouTube Music im
  Video-Modus bevorzugt die Lyrics. Es fehlt, wenn es keine passende Spur gibt, im Song-Modus und
  bei Titeln der Warteschlange (deren Untertitel stehen später im Dokument).
- `href` im `<desc>` zeigt auf das Zusatzdaten-Dokument.

## 3. Das Zusatzdaten-Dokument

`GET <href>` liefert `application/vnd.ytdlna.extras+json`:

```json
{
  "version": 1,
  "videoId": "3LopI4YeC4I",
  "duration": 724,
  "complete": true,
  "skipBy": "phone",
  "segments": [
    {"category": "sponsor", "start": 657.86, "end": 722.18, "action": "skip-once"},
    {"category": "selfpromo", "start": 658.2, "end": 723.74, "action": "manual"},
    {"category": "poi_highlight", "start": 626.19, "action": "jump"}
  ],
  "chapters": [],
  "captions": [
    {"language": "en", "label": "English", "auto": false, "lyrics": false, "default": true,
     "srt": "http://…/captions/3LopI4YeC4I.en.srt", "vtt": "http://…/captions/3LopI4YeC4I.en.vtt"},
    {"language": "", "label": "Lyrics", "auto": false, "lyrics": true, "default": false,
     "srt": "http://…/captions/<id>.lyrics.srt", "vtt": "http://…/captions/<id>.lyrics.vtt"}
  ],
  "lyrics": {
    "provider": "LRCLIB",
    "synced": true,
    "lines": [
      {"text": "We're no strangers to love", "start": 18.6, "end": 22.4,
       "words": [{"text": "We're", "start": 18.6, "end": 18.9}]}
    ]
  }
}
```

Alle Zeiten in Sekunden, bezogen auf die Position, die der Renderer selbst meldet.

**Abholen und aktualisieren**
- Das Handy füllt das Dokument nach und nach: SponsorBlock-Abschnitte und Lyrics kommen ein bis
  einige Sekunden nach dem Titelstart, weitere Untertitel-Sprachen werden im Hintergrund geladen.
- Solange `"complete": false` ist, alle 2 bis 3 s erneut abfragen, mit dem `ETag` der letzten
  Antwort in `If-None-Match`. Ohne Änderung antwortet das Handy mit `304 Not Modified`.
  Spätestens 15 s nach dem Titelstart ist `complete` `true`.
- Auch danach kann sich das Dokument noch ändern (eine spät gefundene Untertitel-Sprache);
  gelegentliches Nachfragen, etwa alle 30 s, schadet nicht.
- `"complete": false` heißt auch: Das Handy spielt den Titel noch nicht (Titel der
  Warteschlange: OpenHome-Playlist, nächster Titel bei AVTransport). Das Dokument ist dann leer;
  der Renderer fragt wie sonst nach (alle 2 bis 3 s mit `If-None-Match`). Sobald der Renderer den
  Titel spielt, folgt das Handy und füllt es.
- `404` nur, wenn das Handy den Titel gar nicht kennt.

## 4. SponsorBlock-Abschnitte (`segments`)

Morphes SponsorBlock mit den Einstellungen des Nutzers je Kategorie:

| `action` | Bedeutung | Vorschlag für die Anzeige |
|---|---|---|
| `skip` | wird automatisch übersprungen | Abschnitt in der Zeitleiste markieren |
| `skip-once` | wird beim ersten Erreichen übersprungen | wie `skip` |
| `manual` | Nutzer überspringt per Knopf | „Überspringen“ anbieten |
| `show` | nur anzeigen | in der Zeitleiste markieren |
| `jump` | Highlight (nur `start`) | „Zum Highlight“ anbieten |

Kategorien wie bei SponsorBlock: `sponsor`, `selfpromo`, `interaction`, `intro`, `outro`,
`preview`, `music_offtopic`, `filler`, `poi_highlight`.

`"skipBy": "phone"`: Das Überspringen erledigt das Handy; der Renderer bekommt dabei einen
gewöhnlichen Seek. Der Renderer soll **nicht** selbst springen, sonst springt es doppelt. Ein
eigener „Überspringen“-Knopf für `manual` und `jump` springt mit einem normalen Seek; das Handy
folgt dem Renderer.

## 5. Untertitel und Lyrics (`captions`, `lyrics`)

- `captions` listet alle bereitstehenden Spuren, jeweils als SRT und WebVTT. `default` ist die
  Spur, die auch als `res` mitkam. `auto` sind automatisch erzeugte Untertitel, `lyrics` die
  Lyrics als Untertitel (nur bei getakteten Lyrics).
- `lyrics` (nur YouTube Music, aus Morphes Lyrics-Funktion; `null`, wenn es keine gibt):
  - `synced: true`: jede Zeile hat `start` und `end`, manche Anbieter liefern zusätzlich
    `words` mit eigenen Zeiten (für eine Wort-für-Wort-Hervorhebung).
  - `synced: false`: nur Text, ohne Zeiten.
  - Gerade im Song-Modus, in dem der Renderer nur Ton und ein Cover bekommt, eignen sich die
    Lyrics zur Anzeige neben dem Cover.

## 6. Vorladen und gleichzeitige Verbindungen

- Der Proxy auf dem Handy bedient beliebig viele Verbindungen gleichzeitig und unterstützt
  `Range`-Anfragen. Ein Renderer kann also den laufenden Titel streamen und zugleich den
  nächsten vorladen.
- Titel der Warteschlange (`…/v/<videoId>/auto`) liefert das Handy, sobald die App ihre Streams
  kennt; den nächsten Titel lädt YouTube Music meist schon vorab.
- Kennt die App die Streams noch nicht, antwortet der Proxy auf eine Vorlade-Anfrage mit
  `503 Service Unavailable` und `Retry-After: 10`. Das heißt „später erneut versuchen“, nicht
  „gibt es nicht“: Der Titel ist nicht als defekt zu markieren. Spätestens wenn der Renderer
  den Titel spielt, folgt das Handy und liefert ihn (die Anfrage wartet dann bis zu 20 s).

## 7. Kurz: was ein Renderer tun sollte

1. `application/vnd.ytdlna.extras+json` und, wenn er Untertitel anzeigen kann, `text/srt` oder
   `text/vtt` in der Sink-Liste ankündigen.
2. Beim Titelwechsel ein `res` mit Untertitel-Format laden, falls vorhanden.
3. Das `<desc>` suchen, das Dokument holen und mit `ETag` nachfragen, bis `complete`.
4. Abschnitte in der Zeitleiste zeigen, Lyrics oder Untertitel anzeigen, nicht selbst
   überspringen.

## 8. Raumgruppe: Sonos-Räume über den Renderer (Entwurf)

**Entwurf:** Die Renderer-Seite ist noch nicht gebaut; Details können sich ändern.

Ein Renderer, der den Ton des Casts selbst an Sonos-Räume weitergibt, bietet auf seinem
MediaRenderer-Gerät den Dienst `urn:ytdlna-org:service:Zones:1` an. Die App zeigt ihn dann wie
eine Sonos-Gruppe: eine Zeile „Wohnzimmer TV + 2“ mit Räume-Knopf. Der Cast geht weiter an den
Renderer.

| Aktion | Zweck |
|---|---|
| `GetZones(SinceVersion)` → `Zones` (JSON), `Version` | Räume, Zustand; leeres `Zones`, wenn sich seit `SinceVersion` nichts geändert hat. Die App fragt bei offenem Dialog alle 2,5 s |
| `SetZoneActive(ZoneId, Active)` | Raum dazu oder weg |
| `SetZoneVolume(ZoneId, Volume)` | Lautstärke eines Raums (0–100) |
| `SetZoneMute(ZoneId, Mute)` | Stummschaltung; die App hebt sie auf, wenn man den Regler eines stummen Raums bewegt |

`Zones`:

```json
{"version": 42, "sync": {"state": "locked"},
 "zones": [{"id": "RINCON_…", "name": "Küche", "group": "RINCON_…", "coordinator": true,
            "active": true, "busy": false, "volume": 20, "mute": false}]}
```

- `id` ist die RINCON-UUID des Sonos. Gruppen, deren Räume beim Renderer `active` sind, zeigt
  die App nicht mehr als eigene Sonos-Zeile (ein direkter Cast nähme sie dem Fernseher weg).
- `busy`: Der Raum spielt gerade etwas anderes; die App bietet ihn nicht zum Hinzufügen an.
- Fehler: 701 (Raum gibt es nicht), 702 (Raum spielt etwas anderes), 704 (Lautsprecher
  antwortet nicht) zeigt die App als Hinweis; bei 703 (kein Raum aktiv) regeln die
  Lautstärketasten wieder den Renderer selbst.
- `sync.state` (`measuring`, `locked`, `lost`) erscheint als Hinweis über den Räumen.
- Hat der Renderer `GroupRenderingControl:1` und spielen Räume mit, regeln die Lautstärketasten
  die Gruppenlautstärke (wie bei einer Sonos-Gruppe).

Ein Mock zum Ausprobieren: `tests/regression/mock/zones_renderer.py`.
