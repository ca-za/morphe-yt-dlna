#!/usr/bin/env python3
"""Mock of a renderer with the Zones service (urn:ytdlna-org:service:Zones:1), for testing the
rooms dialog without the real renderer. Keeps its state in memory; talks to no device.

    python3 zones_renderer.py [port]      (default 49300)
"""
import html
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 49300
ZONES = "urn:ytdlna-org:service:Zones:1"

state = {
    "version": 1,
    "outputDelayMs": 0,
    "lastEnd": "",
    "groupVolume": 20,
    "sync": {"state": "idle", "headStartMs": 500},
    "zones": [
        {"id": "RINCON_MOCK00000000001400", "name": "Mock Küche", "ip": "192.0.2.10", "model": "Bookshelf",
         "group": "RINCON_MOCK00000000001400", "coordinator": False, "active": False, "busy": False,
         "volume": 15, "mute": False},
        {"id": "RINCON_MOCK00000000002400", "name": "Mock Bad", "ip": "192.0.2.11", "model": "S33",
         "group": "RINCON_MOCK00000000002400", "coordinator": False, "active": False, "busy": False,
         "volume": 30, "mute": True},
        {"id": "RINCON_MOCK00000000003400", "name": "Mock Büro", "ip": "192.0.2.12", "model": "S33",
         "group": "RINCON_MOCK00000000003400", "coordinator": False, "active": False, "busy": True,
         "volume": 10, "mute": False},
    ],
}

SERVICES = [
    ("urn:schemas-upnp-org:service:AVTransport:1", "AVTransport"),
    ("urn:schemas-upnp-org:service:ConnectionManager:1", "ConnectionManager"),
    ("urn:schemas-upnp-org:service:RenderingControl:1", "RenderingControl"),
    ("urn:schemas-upnp-org:service:GroupRenderingControl:1", "GroupRenderingControl"),
    (ZONES, "Zones"),
]


def description():
    services = "".join(
        f"<service><serviceType>{t}</serviceType><serviceId>urn:x:serviceId:{n}</serviceId>"
        f"<SCPDURL>/dlna/{n}.xml</SCPDURL><controlURL>/dlna/{n}/control</controlURL>"
        f"<eventSubURL>/dlna/{n}/event</eventSubURL></service>" for t, n in SERVICES)
    return ('<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major>'
            '<minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1'
            '</deviceType><friendlyName>Mock TV</friendlyName><manufacturer>ytdlna</manufacturer>'
            '<modelName>Zones Mock</modelName><UDN>uuid:mock-zones-tv</UDN>'
            f'<serviceList>{services}</serviceList></device></root>')


def bump():
    state["version"] += 1


def arg(body, name):
    m = re.search(rf"<{name}>(.*?)</{name}>", body, re.S)
    return m.group(1) if m else ""


def zone(zone_id):
    return next((z for z in state["zones"] if z["id"] == zone_id), None)


def relayout():
    """First active zone leads; the others join it."""
    active = [z for z in state["zones"] if z["active"]]
    for z in state["zones"]:
        z["coordinator"] = False
        z["group"] = z["id"]
    if active:
        lead = active[0]
        lead["coordinator"] = True
        for z in active:
            z["group"] = lead["id"]
    state["sync"] = ({"state": "locked", "coordinator": active[0]["id"], "offsetMs": 2, "jitterMs": 4,
                      "driftPpm": 7, "headStartMs": 500, "reserveMs": 1030, "underruns": 0}
                     if active else {"state": "idle", "headStartMs": 500})


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("mock: " + fmt % args + "\n")

    def reply(self, status, body, ctype="text/xml; charset=utf-8"):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/description.xml":
            return self.reply(200, description())
        self.reply(404, "")

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        action = self.headers.get("SOAPACTION", "").strip('"').split("#")[-1]
        service = self.headers.get("SOAPACTION", "").strip('"').split("#")[0]
        out = ""
        if action == "GetZones":
            since = int(arg(body, "SinceVersion") or 0)
            zones = "" if since == state["version"] else html.escape(json.dumps(
                {k: v for k, v in state.items() if k != "groupVolume"}))
            out = f"<Zones>{zones}</Zones><Version>{state['version']}</Version>"
        elif action == "SetZoneActive":
            z = zone(arg(body, "ZoneId"))
            active = arg(body, "Active") in ("1", "true")
            if z is None:
                return self.fault(701, "No such room")
            if active and z["busy"]:
                return self.fault(702, "Room busy")
            z["active"] = active
            relayout()
            bump()
        elif action == "SetZoneVolume":
            z = zone(arg(body, "ZoneId"))
            if z is None:
                return self.fault(701, "No such room")
            z["volume"] = int(arg(body, "Volume"))
            bump()
        elif action == "SetZoneMute":
            z = zone(arg(body, "ZoneId"))
            if z is None:
                return self.fault(701, "No such room")
            z["mute"] = arg(body, "Mute").lower() in ("1", "true", "yes")
            bump()
        elif action in ("GetGroupVolume", "SetGroupVolume", "SnapshotGroupVolume") and \
                not any(z["active"] for z in state["zones"]):
            return self.fault(703, "No room active")
        elif action == "GetGroupVolume":
            out = f"<CurrentVolume>{state['groupVolume']}</CurrentVolume>"
        elif action == "SetGroupVolume":
            state["groupVolume"] = int(arg(body, "DesiredVolume"))
            bump()
        elif action == "GetVolume":
            out = "<CurrentVolume>40</CurrentVolume>"
        elif action == "GetProtocolInfo":
            out = "<Source></Source><Sink>http-get:*:video/mp4:*</Sink>"
        elif action == "GetTransportInfo":
            out = ("<CurrentTransportState>STOPPED</CurrentTransportState>"
                   "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>")
        sys.stderr.write(f"mock: {action} -> version {state['version']}\n")
        self.reply(200, '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
                        f'<s:Body><u:{action}Response xmlns:u="{service}">{out}</u:{action}Response>'
                        '</s:Body></s:Envelope>')

    def fault(self, code, text):
        self.reply(500, '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
                        '<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>'
                        '<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">'
                        f'<errorCode>{code}</errorCode><errorDescription>{text}</errorDescription>'
                        '</UPnPError></detail></s:Fault></s:Body></s:Envelope>')


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
