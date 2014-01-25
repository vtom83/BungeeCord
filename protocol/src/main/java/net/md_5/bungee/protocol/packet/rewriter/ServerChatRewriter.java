package net.md_5.bungee.protocol.packet.rewriter;

import io.netty.buffer.ByteBuf;
import net.md_5.bungee.protocol.DefinedPacket;

public class ServerChatRewriter extends PacketRewriter
{

    @Override
    public void rewrite(ByteBuf in, ByteBuf out)
    {
        out.writeBytes( in.readBytes( in.readableBytes() ) );
        out.writeByte( 0 );
    }
}