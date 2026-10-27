package app.ytdlna.extension;

import android.os.Handler;

import java.util.function.BooleanSupplier;

/** Plain UPnP AV renderer: one title at a time through AVTransport. */
final class AvTransportRenderer extends Renderer {
    private volatile String token;

    AvTransportRenderer(DlnaDevice device, String baseUrl, Listener listener, Handler main, BooleanSupplier audioOnly) {
        super(device, baseUrl, listener, main, audioOnly);
    }

    @Override
    boolean subscribe() {
        if (device.avTransportEventUrl != null) token = NativeProxy.nativeSubscribe(device.avTransportEventUrl, baseUrl);
        return token != null;
    }

    @Override
    void unsubscribe() {
        String t = token;
        token = null;
        if (t != null) NativeProxy.nativeUnsubscribe(t);
    }

    @Override
    boolean handleEvent(String eventToken, String name, String value) {
        if (!eventToken.equals(token)) return false;
        if (name.equals("SubscriptionLost")) {
            main.post(listener::onEventsLost);
        } else if (name.equals("TransportState")) {
            main.post(() -> listener.onTransportState(value));
        }
        return true;
    }

    @Override
    String pollState() throws Exception {
        return DlnaController.transportState(device);
    }

    @Override
    void load(Load load) throws Exception {
        String url = baseUrl + load.media.path;
        DlnaController.play(device, url, load.media.mimeType, load.track, load.art, load.media.video);
    }

    @Override
    void resume() throws Exception {
        DlnaController.resume(device);
    }

    @Override
    void pause() throws Exception {
        DlnaController.pause(device);
    }

    @Override
    void stop() throws Exception {
        DlnaController.stop(device);
    }

    @Override
    void seek(long positionMs, boolean playing) throws Exception {
        DlnaController.seek(device, positionMs);
    }

    @Override
    long position() throws Exception {
        return DlnaController.position(device);
    }
}
