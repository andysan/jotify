package de.felixbruns.jotify.gateway;

import de.felixbruns.jotify.api.exceptions.ProtocolException;
import de.felixbruns.jotify.api.media.Track;
import de.felixbruns.jotify.api.player.ChannelPlayer;
import de.felixbruns.jotify.api.player.PlaybackListener;
import de.felixbruns.jotify.api.player.Player;
import de.felixbruns.jotify.api.protocol.Protocol;
import de.felixbruns.jotify.api.protocol.channel.ChannelCallback;

public class GatewayPlayer implements Player {
	private Protocol      protocol;
	private ChannelPlayer player;
	private float         volume;
	
	public GatewayPlayer(Protocol protocol){
		if(protocol == null){
			throw new IllegalArgumentException("Protocol can't be 'null'.");
		}
		
		this.protocol = protocol;
		this.player   = null;
		this.volume   = 1.0f;
	}
	
	public void play(Track track, PlaybackListener listener){
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send play request (token notify + AES key). */
		try{
			this.protocol.sendPlayRequest(callback, track);
		}
		catch(ProtocolException e){
			return;
		}
		
		/* Get AES key. */
		byte[] key = callback.get();
		
		/* Create channel player. */
		this.player = new ChannelPlayer(this.protocol, track, key, listener);
		this.player.volume(this.volume);
		
		/* Start playing. */
		this.play();
	}
	
	public void play(){
		if(this.player != null){
			this.player.play();
		}
	}
	
	public void pause(){
		if(this.player != null){
			this.player.pause();
		}
	}
	
	public void stop(){
		if(this.player != null){
			this.player.stop();
			
			this.player = null;
		}
	}
	
	public int length(){
		if(this.player != null){
			return this.player.length();
		}
		
		return -1;
	}
	
	public int position(){
		if(this.player != null){
			return this.player.position();
		}
		
		return -1;
	}
	
	public float volume(){
		if(this.player != null){
			return this.player.volume();
		}
		
		return -1;
	}
	
	public void volume(float volume){
		this.volume = volume;
		
		if(this.player != null){
			this.player.volume(this.volume);
		}
	}
}
