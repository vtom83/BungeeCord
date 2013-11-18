package net.md_5.bungee.connection;

import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.util.concurrent.ScheduledFuture;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.*;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.http.HttpClient;
import net.md_5.bungee.netty.*;
import net.md_5.bungee.netty.decoders.CipherDecoder;
import net.md_5.bungee.netty.encoders.CipherEncoder;
import net.md_5.bungee.netty.decoders.PacketDecoder;
import net.md_5.bungee.protocol.Forge;
import net.md_5.bungee.protocol.MinecraftInput;
import net.md_5.bungee.protocol.Vanilla;
import net.md_5.bungee.protocol.packet.*;
import net.md_5.bungee.api.AbstractReconnectHandler;
import net.md_5.bungee.api.event.PlayerHandshakeEvent;
import net.md_5.bungee.protocol.packet.protocolhack.*;

@RequiredArgsConstructor
public class InitialHandler extends PacketHandler implements PendingConnection
{

    private final ProxyServer bungee;
    private ChannelWrapper ch;
    @Getter
    private final ListenerInfo listener;
    @Getter
    private Packet1Login forgeLogin;
    @Getter
    private Packet2Handshake handshake;
    private PacketFDEncryptionRequest request164;
    private PacketEncryptionRequest request172;
    @Getter
    private List<PacketFAPluginMessage> loginMessages = new ArrayList<>();
    @Getter
    private List<PacketFAPluginMessage> registerMessages = new ArrayList<>();
    private State thisState = State.HANDSHAKE;
    private SecretKey sharedKey;
    private final Unsafe unsafe = new Unsafe()
    {
        @Override
        public void sendPacket(DefinedPacket packet)
        {
            ch.write( packet );
        }
    };
    @Getter
    private boolean onlineMode = BungeeCord.getInstance().config.isOnlineMode();
    private ScheduledFuture<?> pingFuture;
    private InetSocketAddress vHost;
    private byte version = -1;
    private PacketHandshake ver17handshake;

    private enum State
    {

        HANDSHAKE, ENCRYPT, LOGIN, FINISHED;
    }

    @Override
    public void connected(ChannelWrapper channel) throws Exception
    {
        this.ch = channel;
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        disconnect( ChatColor.RED + Util.exception( t ) );
    }

    @Override
    public void handle(PacketFAPluginMessage pluginMessage) throws Exception
    {
        if ( pluginMessage.getTag().equals( "MC|PingHost" ) )
        {
            if ( pingFuture.cancel( false ) )
            {
                MinecraftInput in = pluginMessage.getMCStream();
                version = in.readByte();
                String connectHost = in.readString();
                int connectPort = in.readInt();
                this.vHost = new InetSocketAddress( connectHost, connectPort );

                respondToPing();
            }

            return;
        }

        // TODO: Unregister?
        if ( pluginMessage.getTag().equals( "REGISTER" ) )
        {
            registerMessages.add(pluginMessage);
        } else
        {
            loginMessages.add(pluginMessage);
        }
    }

    private void respondToPing()
    {
        ServerInfo forced = AbstractReconnectHandler.getForcedHost( this );
        final String motd = ( forced != null ) ? forced.getMotd() : listener.getMotd();

        Callback<ServerPing> pingBack = new Callback<ServerPing>()
        {
            @Override
            public void done(ServerPing result, Throwable error)
            {
                if ( error != null )
                {
                    result = new ServerPing( (byte) -1, "-1", "Error pinging remote server: " + Util.exception( error ), -1, -1 );
                }
                result = bungee.getPluginManager().callEvent( new ProxyPingEvent( InitialHandler.this, result ) ).getResponse();

                String kickMessage = ChatColor.DARK_BLUE
                        + "\00" + result.getProtocolVersion()
                        + "\00" + result.getGameVersion()
                        + "\00" + result.getMotd()
                        + "\00" + result.getCurrentPlayers()
                        + "\00" + result.getMaxPlayers();
                BungeeCord.getInstance().getConnectionThrottle().unthrottle( getAddress().getAddress() );
                disconnect( kickMessage );
            }
        };

        if ( forced != null && listener.isPingPassthrough() )
        {
            forced.ping( pingBack );
        } else
        {
            pingBack.done( new ServerPing( bungee.getProtocolVersion(), bungee.getGameVersion(), motd, bungee.getOnlineCount(), listener.getMaxPlayers() ), null );
        }
    }

    @Override
    public void handle(PacketFEPing ping) throws Exception
    {
        pingFuture = ch.getHandle().eventLoop().schedule( new Runnable()
        {
            @Override
            public void run()
            {
                respondToPing();
            }
        }, 200, TimeUnit.MILLISECONDS );
    }

    @Override
    public void handle(PacketPingRequest pingRequest)
    {
        JsonObject result = new JsonObject();
        JsonObject version = new JsonObject();
        version.add( "name", new JsonPrimitive( "1.7.2" ) );
        version.add( "protocol", new JsonPrimitive( 4 ) );
        JsonObject players = new JsonObject();
        players.add( "max", new JsonPrimitive( listener.getMaxPlayers() ) );
        players.add( "online", new JsonPrimitive( bungee.getOnlineCount() ) );
        players.add( "sample", new JsonArray() ); // empty array
        result.add( "version", version );
        result.add( "players", players );
        result.add( "description", new JsonPrimitive( listener.getMultilineMotd().replaceAll( "\\\\n", "\n" ) ) );
        String favicon = bungee.getFavicon();
        if ( favicon != null ) {
            result.add( "favicon", new JsonPrimitive( favicon ) );
        }
        unsafe().sendPacket( new PacketPingResponse( result.toString() ) );
    }

    @Override
    public void handle(PacketPing ping)
    {
        unsafe().sendPacket( ping );
        ch.close();
    }

    @Override
    public void handle(Packet1Login login) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN, "Not expecting FORGE LOGIN" );
        Preconditions.checkState( forgeLogin == null, "Already received FORGE LOGIN" );
        forgeLogin = login;

        ch.getHandle().pipeline().get( PacketDecoder.class ).setProtocol( Forge.getInstance() );
    }

    @Override
    public void handle(PacketHandshake handshake) {
        Preconditions.checkState( thisState == State.HANDSHAKE, "Not expecting HANDSHAKE" );
        this.vHost = new InetSocketAddress( handshake.getServerAddress(), handshake.getServerPort() );
        this.ver17handshake = handshake;
    }

    @Override
    public void handle(PacketLoginStart loginStart) {
        handshake = new Packet2Handshake( (byte) 78, loginStart.getUser(), ver17handshake.getServerAddress(),
                ver17handshake.getServerPort() );

        bungee.getPluginManager().callEvent( new PlayerHandshakeEvent( InitialHandler.this, handshake ) );

        if ( handshake.getProtocolVersion() > Vanilla.PROTOCOL_VERSION )
        {
            disconnect( bungee.getTranslation( "outdated_server" ) );
        } else if ( handshake.getProtocolVersion() < Vanilla.PROTOCOL_VERSION )
        {
            disconnect( bungee.getTranslation( "outdated_client" ) );
        }

        if ( handshake.getUsername().length() > 16 )
        {
            disconnect( "Cannot have username longer than 16 characters" );
            return;
        }

        int limit = BungeeCord.getInstance().config.getPlayerLimit();
        if ( limit > 0 && bungee.getOnlineCount() > limit )
        {
            disconnect( bungee.getTranslation( "proxy_full" ) );
            return;
        }

        // If offline mode and they are already on, don't allow connect
        if ( !isOnlineMode() && bungee.getPlayer( handshake.getUsername() ) != null )
        {
            disconnect( bungee.getTranslation( "already_connected" ) );
            return;
        }

        unsafe().sendPacket( request172 = EncryptionUtil.encryptRequest172(this.onlineMode) ); // packet mapping should take care of this
        thisState = State.ENCRYPT;
    }

    @Override
    public void handle(Packet2Handshake handshake) throws Exception
    {
        Preconditions.checkState( thisState == State.HANDSHAKE, "Not expecting HANDSHAKE" );
        this.handshake = handshake;
        this.vHost = new InetSocketAddress( handshake.getHost(), handshake.getPort() );
        bungee.getLogger().log( Level.INFO, "{0} has connected", this );

        bungee.getPluginManager().callEvent( new PlayerHandshakeEvent( InitialHandler.this, handshake ) );

        if ( handshake.getProtocolVersion() > Vanilla.PROTOCOL_VERSION )
        {
            disconnect( bungee.getTranslation( "outdated_server" ) );
        } else if ( handshake.getProtocolVersion() < Vanilla.PROTOCOL_VERSION )
        {
            disconnect( bungee.getTranslation( "outdated_client" ) );
        }

        if ( handshake.getUsername().length() > 16 )
        {
            disconnect( "Cannot have username longer than 16 characters" );
            return;
        }

        int limit = BungeeCord.getInstance().config.getPlayerLimit();
        if ( limit > 0 && bungee.getOnlineCount() > limit )
        {
            disconnect( bungee.getTranslation( "proxy_full" ) );
            return;
        }

        // If offline mode and they are already on, don't allow connect
        if ( !isOnlineMode() && bungee.getPlayer( handshake.getUsername() ) != null )
        {
            disconnect( bungee.getTranslation( "already_connected" ) );
            return;
        }

        unsafe().sendPacket( PacketConstants.I_AM_BUNGEE );
        unsafe().sendPacket( PacketConstants.FORGE_MOD_REQUEST );

        unsafe().sendPacket( request164 = EncryptionUtil.encryptRequest164(this.onlineMode) );
        thisState = State.ENCRYPT;
    }

    @Override
    public void handle(final PacketFCEncryptionResponse encryptResponse) throws Exception
    {
        Preconditions.checkState( thisState == State.ENCRYPT, "Not expecting ENCRYPT" );

        sharedKey = EncryptionUtil.getSecret( encryptResponse, request164 );
        Cipher decrypt = EncryptionUtil.getCipher( Cipher.DECRYPT_MODE, sharedKey );
        ch.addBefore( PipelineUtils.DUAL_PROTOCOL_PACKET_DECODER, PipelineUtils.DECRYPT_HANDLER, new CipherDecoder( decrypt ) );

        if ( this.onlineMode )
        {
            String encName = URLEncoder.encode( InitialHandler.this.getName(), "UTF-8" );

            MessageDigest sha = MessageDigest.getInstance( "SHA-1" );
            for ( byte[] bit : new byte[][]
            {
                request164.getServerId().getBytes( "ISO_8859_1" ), sharedKey.getEncoded(), EncryptionUtil.keys.getPublic().getEncoded()
            } )
            {
                sha.update( bit );
            }

            String encodedHash = URLEncoder.encode( new BigInteger( sha.digest() ).toString( 16 ), "UTF-8" );
            String authURL = "http://session.minecraft.net/game/checkserver.jsp?user=" + encName + "&serverId=" + encodedHash;

            Callback<String> handler = new Callback<String>()
            {
                @Override
                public void done(String result, Throwable error)
                {
                    if ( error == null )
                    {
                        if ( "YES".equals( result ) )
                        {
                            finish( false );
                        } else
                        {
                            disconnect( "Not authenticated with Minecraft.net" );
                        }
                    } else
                    {
                        disconnect( bungee.getTranslation( "mojang_fail" ) );
                        bungee.getLogger().log( Level.SEVERE, "Error authenticating " + getName() + " with minecraft.net", error );
                    }
                }
            };

            HttpClient.get( authURL, ch.getHandle().eventLoop(), handler );
        } else
        {
            // Send login success
        }
    }

    @Override
    public void handle(PacketEncryptionResponse encryptionResponse) throws Exception {
        Preconditions.checkState( thisState == State.ENCRYPT, "Not expecting ENCRYPT" );

        sharedKey = EncryptionUtil.getSecret( encryptionResponse, request172 );
        Cipher decrypt = EncryptionUtil.getCipher( Cipher.DECRYPT_MODE, sharedKey );
        ch.addBefore( PipelineUtils.DUAL_PROTOCOL_PACKET_DECODER, PipelineUtils.DECRYPT_HANDLER, new CipherDecoder( decrypt ) );

        if ( this.onlineMode )
        {
            String encName = URLEncoder.encode( InitialHandler.this.getName(), "UTF-8" );

            MessageDigest sha = MessageDigest.getInstance( "SHA-1" );
            for ( byte[] bit : new byte[][]
                    {
                            request172.getServerId().getBytes( "ISO_8859_1" ), sharedKey.getEncoded(), EncryptionUtil.keys.getPublic().getEncoded()
                    } )
            {
                sha.update( bit );
            }

            String encodedHash = URLEncoder.encode( new BigInteger( sha.digest() ).toString( 16 ), "UTF-8" );
            String authURL = "http://session.minecraft.net/game/checkserver.jsp?user=" + encName + "&serverId=" + encodedHash;

            Callback<String> handler = new Callback<String>()
            {
                @Override
                public void done(String result, Throwable error)
                {
                    if ( error == null )
                    {
                        if ( "YES".equals( result ) )
                        {
                            finish( true );
                        } else
                        {
                            disconnect( "Not authenticated with Minecraft.net" );
                        }
                    } else
                    {
                        disconnect( bungee.getTranslation( "mojang_fail" ) );
                        bungee.getLogger().log( Level.SEVERE, "Error authenticating " + getName() + " with minecraft.net", error );
                    }
                }
            };

            HttpClient.get( authURL, ch.getHandle().eventLoop(), handler );
        } else
        {
            finish( true );
        }
    }

    private void finish(final boolean ver17)
    {
        // Check for multiple connections
        ProxiedPlayer old = bungee.getPlayer( handshake.getUsername() );
        if ( old != null )
        {
            old.disconnect( bungee.getTranslation( "already_connected" ) );
        }

        Callback<LoginEvent> complete = new Callback<LoginEvent>()
        {
            @Override
            public void done(LoginEvent result, Throwable error)
            {
                if ( result.isCancelled() )
                {
                    disconnect( result.getCancelReason() );
                }
                if ( ch.isClosed() )
                {
                    return;
                }
                thisState = InitialHandler.State.LOGIN;

                ch.getHandle().eventLoop().execute( new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if ( ch.getHandle().isActive() )
                        {
                            if ( !ver17 ) {
                                unsafe().sendPacket( new PacketFCEncryptionResponse( new byte[ 0 ], new byte[ 0 ] ) );
                            }
                            if ( !ch.getHandle().pipeline().names().contains( PipelineUtils.TRANSLATOR_ENCODE_HANDLER ) ) {
                            }
                            try
                            {
                                Cipher encrypt = EncryptionUtil.getCipher( Cipher.ENCRYPT_MODE, sharedKey );
                                ch.addBefore( PipelineUtils.DECRYPT_HANDLER, PipelineUtils.ENCRYPT_HANDLER, new CipherEncoder( encrypt ) );
                                if ( ver17 ) {
                                    unsafe.sendPacket( new PacketLoginSuccess( getName() ) );
                                    try {
                                        handle( new PacketCDClientStatus( (byte)0 ) );
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            } catch ( GeneralSecurityException ex )
                            {
                                disconnect( "Cipher error: " + Util.exception( ex ) );
                            }
                        }
                    }
                } );
            }
        };

        // fire login event
        bungee.getPluginManager().callEvent( new LoginEvent( InitialHandler.this, complete ) );
    }

    @Override
    public void handle(PacketCDClientStatus clientStatus) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN, "Not expecting LOGIN" );

        UserConnection userCon = new UserConnection( bungee, ch, getName(), this );
        userCon.init();

        bungee.getPluginManager().callEvent( new PostLoginEvent( userCon ) );

        ch.getHandle().pipeline().get( HandlerBoss.class ).setHandler( new UpstreamBridge( bungee, userCon ) );

        ServerInfo server;
        if ( bungee.getReconnectHandler() != null )
        {
            server = bungee.getReconnectHandler().getServer( userCon );
        } else
        {
            server = AbstractReconnectHandler.getForcedHost( this );
        }
        userCon.connect( server, true );

        thisState = State.FINISHED;
        throw new CancelSendSignal();
    }

    @Override
    public synchronized void disconnect(String reason)
    {
        if ( !ch.isClosed() )
        {
            unsafe().sendPacket( new PacketFFKick( reason ) );
            ch.close();
        }
    }

    @Override
    public String getName()
    {
        return ( handshake == null ) ? null : handshake.getUsername();
    }

    @Override
    public byte getVersion()
    {
        return ( handshake == null ) ? version : handshake.getProtocolVersion();
    }

    @Override
    public InetSocketAddress getVirtualHost()
    {
        return vHost;
    }

    @Override
    public InetSocketAddress getAddress()
    {
        return (InetSocketAddress) ch.getHandle().remoteAddress();
    }

    @Override
    public Unsafe unsafe()
    {
        return unsafe;
    }

    public void setOnlineMode(boolean onlineMode)
    {
        Preconditions.checkState( thisState == State.HANDSHAKE, "Can only set online mode status whilst handshaking" );
        this.onlineMode = onlineMode;
    }

    @Override
    public String toString()
    {
        return "[" + ( ( getName() != null ) ? getName() : getAddress() ) + "] <-> InitialHandler";
    }
}
