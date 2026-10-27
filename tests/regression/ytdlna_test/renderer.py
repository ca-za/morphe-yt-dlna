"""Direct control and observation of the DLNA renderer from the host, over SOAP."""

import re
import urllib.request
from html import unescape
from urllib.parse import urljoin

SOAP_ENVELOPE = ('<?xml version="1.0" encoding="utf-8"?>'
                 '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                 's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                 '<u:{action} xmlns:u="{service}">{args}</u:{action}></s:Body></s:Envelope>')

OPENHOME_STATES = {"Playing": "PLAYING", "Paused": "PAUSED_PLAYBACK", "Stopped": "STOPPED",
                   "Buffering": "TRANSITIONING"}


class Renderer:
    def __init__(self, description_url: str):
        xml = urllib.request.urlopen(description_url, timeout=5).read().decode("utf-8", "replace")
        self.name = _tag(xml, "friendlyName")
        self.services: dict[str, tuple[str, str]] = {}
        for block in re.findall(r"<service>(.*?)</service>", xml, re.S):
            service_type = _tag(block, "serviceType")
            short = service_type.split(":")[-2]
            self.services[short] = (service_type, urljoin(description_url, _tag(block, "controlURL")))

    @property
    def openhome(self) -> bool:
        return "Playlist" in self.services

    def soap(self, service: str, action: str, args: str = "") -> str:
        service_type, url = self.services[service]
        body = SOAP_ENVELOPE.format(action=action, service=service_type, args=args).encode()
        request = urllib.request.Request(url, body, {
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{service_type}#{action}"'})
        return urllib.request.urlopen(request, timeout=5).read().decode("utf-8", "replace")

    def state(self) -> str:
        if self.openhome:
            return OPENHOME_STATES.get(_tag(self.soap("Playlist", "TransportState"), "Value"), "?")
        return _tag(self.soap("AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>"),
                    "CurrentTransportState")

    def position_ms(self) -> int:
        if "Time" in self.services:
            return int(_tag(self.soap("Time", "Time"), "Seconds") or 0) * 1000
        rel = _tag(self.soap("AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>"), "RelTime")
        h, m, s = (rel or "0:0:0").split(":")
        return int((int(h) * 3600 + int(m) * 60 + float(s)) * 1000)

    def metadata(self) -> dict:
        """Title, artist, cover and kind of the current title."""
        if "Info" in self.services:
            didl = unescape(_tag(self.soap("Info", "Track"), "Metadata"))
        else:
            didl = unescape(_tag(self.soap("AVTransport", "GetMediaInfo", "<InstanceID>0</InstanceID>"),
                                 "CurrentURIMetaData"))
        return {"title": unescape(_tag(didl, "dc:title")), "artist": unescape(_tag(didl, "upnp:artist")),
                "art": unescape(_tag(didl, "upnp:albumArtURI")), "class": _tag(didl, "upnp:class"),
                "protocol": (re.search(r'protocolInfo="([^"]*)"', didl) or [None, ""])[1]}

    def playlist_size(self) -> int:
        if not self.openhome:
            return 0
        ids = _tag(self.soap("Playlist", "IdArray"), "Array")
        import base64
        return len(base64.b64decode(ids)) // 4 if ids else 0

    def volume(self) -> int:
        return int(_tag(self.soap("RenderingControl", "GetVolume",
                                  "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume") or -1)

    def pause(self) -> None:
        if self.openhome:
            self.soap("Playlist", "Pause")
        else:
            self.soap("AVTransport", "Pause", "<InstanceID>0</InstanceID>")

    def play(self) -> None:
        if self.openhome:
            self.soap("Playlist", "Play")
        else:
            self.soap("AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    def next(self) -> None:
        if self.openhome:
            self.soap("Playlist", "Next")
        else:
            self.soap("AVTransport", "Next", "<InstanceID>0</InstanceID>")


def _tag(xml: str, name: str) -> str:
    match = re.search(rf"<{re.escape(name)}(?:\s[^>]*)?>(.*?)</{re.escape(name)}>", xml or "", re.S)
    return match.group(1).strip() if match else ""
