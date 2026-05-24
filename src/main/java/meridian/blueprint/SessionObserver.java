package meridian.blueprint;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.SetClientId;

/**
 * MONITOR-position observer on the Default channel — its sole job is to hand
 * the live {@link ProxySession} to {@link PreviewController} so we have a
 * pipe back to the client when the user invokes a preview action.
 *
 * <p>{@code ShowTriggerVolumePastePrefabPreview} rides the Default channel
 * so a session captured from any Default-channel packet is the right target.
 * We pin on the first eligible packet we see in either direction and never
 * reassign — there's exactly one client per session.
 */
final class SessionObserver implements PacketHandler {

    private final PreviewController controller;

    SessionObserver(PreviewController controller) {
        this.controller = controller;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetClientId || packet instanceof JoinWorld) {
            controller.bindSession(session);
        }
        return Action.FORWARD;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof ClientMovement) {
            // ClientMovement also rides Default — captures the session even
            // before the server has acknowledged us with SetClientId.
            controller.bindSession(session);
        }
        return Action.FORWARD;
    }
}
