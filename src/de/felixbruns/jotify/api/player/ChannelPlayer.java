package de.felixbruns.jotify.api.player;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Semaphore;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import de.felixbruns.jotify.api.cache.SubstreamCache;
import de.felixbruns.jotify.api.exceptions.ProtocolException;
import de.felixbruns.jotify.api.media.Track;
import de.felixbruns.jotify.api.protocol.Protocol;
import de.felixbruns.jotify.api.protocol.channel.Channel;
import de.felixbruns.jotify.api.protocol.channel.ChannelListener;
import de.felixbruns.jotify.api.util.MathUtilities;

public class ChannelPlayer implements Runnable, Player, ChannelListener {
	/* 
	 * Cipher implementation, key and IV
	 * for decryption of audio stream.
	 */
	private Cipher cipher;
	private Key    key;
	private byte[] iv;
	
	/* Streams, audio decoding and output. */
	private PipedInputStream  input;
	private PipedOutputStream output;
	private SpotifyOggHeader  spotifyOggHeader;
	private AudioInputStream  audioStream;
	private AudioFormat       audioFormat;
	private SourceDataLine    audioLine;
	
	/* Playback listener and playback position. */
	private PlaybackListener listener;
	private long             position;
	
	/* 
	 * Protocol, Track object and variables for
	 * substream requesting and handling.
	 */
	private Protocol protocol;
	private Track    track;
	private int      streamOffset;
	private int      streamLength;
	private int      receivedLength;
	private boolean  loading;
	
	/* Caching of substreams. */
	private SubstreamCache cache;
	private byte[]         cacheData;
	
	/* Current player status and semaphores for pausing. */
	private boolean   active;
	private Semaphore pause;
	
	/**
	 * Creates a new ChannelPlayer for decrypting and playing audio from a
	 * protocol channel.
	 * 
	 * @param protocol A {@link Protocol} instance which will be used to
	 *                 communicate with the server.
	 * @param track    A {@link Track} object identifying the track to be
	 *                 streamed.
	 * @param key      The corresponding AES key for decrypting the stream.
	 * @param listener A {@link PlaybackListener} or null.
	 * 
	 * @see Protocol
	 * @see Track
	 * @see PlaybackListener
	 */
	public ChannelPlayer(Protocol protocol, Track track, byte[] key, PlaybackListener listener){
		/* Set protocol, track, playback listener and cache. */
		this.protocol = protocol;
		this.track    = track;
		this.listener = listener;
		this.position = 0;
		this.cache    = new SubstreamCache();
		
		/* Initialize AES cipher. */		
		try{
			/* Get AES cipher instance. */
			this.cipher = Cipher.getInstance("AES/CTR/NoPadding");
			
			/* Create secret key from bytes and set initial IV. */
			this.key = new SecretKeySpec(key, "AES");
			this.iv  = new byte[]{
				(byte)0x72, (byte)0xe0, (byte)0x67, (byte)0xfb,
				(byte)0xdd, (byte)0xcb, (byte)0xcf, (byte)0x77,
				(byte)0xeb, (byte)0xe8, (byte)0xbc, (byte)0x64,
				(byte)0x3f, (byte)0x63, (byte)0x0d, (byte)0x93
			};
			
			/* Initialize cipher with key and IV in encrypt mode. */
			this.cipher.init(Cipher.ENCRYPT_MODE, this.key, new IvParameterSpec(this.iv));
		}
		catch(NoSuchAlgorithmException e){
			throw new RuntimeException("AES/CTR is not available!", e);
		}
		catch(NoSuchPaddingException e){
			throw new RuntimeException("'NoPadding' is not available! mmh. yeah.", e);
		}
		catch (InvalidKeyException e){
			throw new RuntimeException("Invalid key!", e);
		}
		catch (InvalidAlgorithmParameterException e){
			throw new RuntimeException("Invalid IV!", e);
		}
		
		/* Create piped streams and connect them (10 seconds, 160 kbit ogg buffer). */
		try{
			this.input  = new PipedInputStream(160 * 1024 * 10 / 8);
			this.output = new PipedOutputStream(this.input);
		}
		catch(IOException e){
			throw new RuntimeException("Can't connect piped streams!", e);
		}
		
		/* Audio will be initialized in "open" method. */
		this.spotifyOggHeader = null;
		this.audioStream      = null;
		this.audioLine        = null;
		this.active           = false;
		this.pause            = new Semaphore(1);
		
		/* Acquire permit. Status is paused. */
		this.pause.acquireUninterruptibly();
		
		/* Set substream offset and length (5 seconds, 160 kbit ogg data). */
		this.streamOffset = 0;
		this.streamLength = 160 * 1024 * 5 / 8;
		this.loading      = false;
		
		/* 
		 * Send first substream request so we can provide
		 * enough data on the piped output stream. Check
		 * for cached substream first.
		 */
		String hash = this.cache.hash(this.track, this.streamOffset, this.streamLength);
		
		if(this.cache != null && this.cache.contains("substream", hash)){
			this.cache.load("substream", hash, this);
		}
		else{
			try{
				this.loading = true;
				
				this.protocol.sendSubstreamRequest(this, this.track, this.streamOffset, this.streamLength);
			}
			catch(ProtocolException e){
				throw new RuntimeException("Error sending substream request!", e);
			}
		}
		
		/* Open input stream for playing. */
		try{
			this.open(this.input);
		}
		catch(Exception e){
			throw new RuntimeException("Can't open input stream for playing!", e);
		}
	}
	
	/* Open an input stream and start decoding it,
	 * set up audio stuff when AudioInputStream
	 * was sucessfully created.
	 */
	private void open(InputStream stream) throws IOException, UnsupportedAudioFileException, LineUnavailableException {
		/* Audio streams and formats. */
		AudioInputStream sourceStream;
		AudioFormat      sourceFormat;
		AudioFormat      targetFormat;
		
		/* Spotify specific ogg header. */
		byte[] header = new byte[167];
		
		/* Read and decode header. */
		stream.read(header);
		
		this.spotifyOggHeader = new SpotifyOggHeader(header);
		
		/* Get audio source stream */
		sourceStream = AudioSystem.getAudioInputStream(stream);
		
		/* Get source format and set target format. */
		sourceFormat = sourceStream.getFormat();
		targetFormat = new AudioFormat(
			sourceFormat.getSampleRate(), 16,
			sourceFormat.getChannels(), true, false
		);
		
		this.audioFormat = targetFormat;
		
		/* Get target audio stream */
		this.audioStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
		
		/* Get line info for target format. */
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetFormat);
		
		/* Get line for obtained line info. */
		this.audioLine = (SourceDataLine)AudioSystem.getLine(info);
		
		/* Finally open line for playback. */
		this.audioLine.open();
		
		/* Set player status. */
		this.active = true;
		
		/* Start thread which writes data to the line. */
		new Thread(this, "ChannelPlayer-Thread").start();
	}
	
	public void run(){
		/* Buffer for data and number of bytes read */
		byte[] buffer = new byte[1024];
		int need = (int)(this.audioFormat.getSampleRate() * this.audioFormat.getSampleSizeInBits() * 5 / 8);
		int have = 0;
		int read = 0;
		
		this.position = 0;
		
		/* Fire playback started and stopped events (track is paused). */
		if(this.listener != null){
			this.listener.playbackStarted(this.track);
			this.listener.playbackStopped(this.track);
		}
		
		/* Read-write loop. */
		while(this.active && read != -1){
			/* Wait if we're paused. */
			this.pause.acquireUninterruptibly();
			
			/* Check if we have enough data (we want at least 5 seconds of audio). */
			try{
				have = this.audioStream.available();
			}
			catch(IOException e){
				have = 0;
			}
			
			/* Do the actual check, but only if we're not loading data right now. */
			if(!this.loading && have < need){
				/* Set flag that we're loading data now. */
				this.loading = true;
				
				/* Increment substream offset. */
				this.streamOffset += this.streamLength;
				
				/* Create cache hash. */
				String hash = this.cache.hash(this.track, this.streamOffset, this.streamLength);
				
				/* Check for cached substream. */
				if(this.cache != null && this.cache.contains("substream", hash)){
					this.cache.load("substream", hash, this);
				}
				/* Otherwise, send next substream request. */
				else{
					try{
						this.protocol.sendSubstreamRequest(
							this, this.track, this.streamOffset, this.streamLength
						);
					}
					catch(ProtocolException e){
						System.err.println("Error sending substream request!");
						
						return;
					}
				}
			}
			
			/* Read data from audio stream and write it to the audio line. */
			try{
				if((read = this.audioStream.read(buffer, 0, buffer.length)) > 0){
					this.audioLine.write(buffer, 0, read);
				}
			}
			catch(IOException e){
				e.printStackTrace();
				
				/* Don't care. */
			}
			
			/* Get current playback position. */
			long position = this.audioLine.getMicrosecondPosition();
			
			/* Fire playback position event about every 100 ms. */
			if(this.listener != null && position - this.position > 100000){
				this.listener.playbackPosition(this.track, (int)(position / 1000)); /* us -> ms */
				
				/* Update last postition. */
				this.position = position;
			}
			
			/* Release permit, so we can be paused. */
			this.pause.release();
		}
		
		/* Block until all data is processed, then close audio line. */
		this.audioLine.drain();
		this.audioLine.close();
		
		/* Fire playback finished event. (Note: Not when closed manually!) */
		if(this.listener != null && this.active){
			this.listener.playbackFinished(this.track);
		}
		
		/* Set player status. */
		this.active = false;
	}
	
	public void play(Track track, PlaybackListener listener) {
		this.play();
	}
	
	/**
	 * Start playback or continue playing if "stop" was called before.
	 */
	public void play(){
		/* Start audio line again. */
		this.audioLine.start();
		
		/* Release permit to resume IO thread. */
		this.pause.release();
		
		/* Fire playback resumed event. */
		if(this.listener != null){
			this.listener.playbackResumed(this.track);
		}
	}
	
	/**
	 * Stop playback of audio until "play" is called again.
	 */
	public void pause(){
		/* Stop audio line. */
		this.audioLine.stop();
		
		/* Acquire a permit to stop IO thread. */
		this.pause.acquireUninterruptibly();
		
		/* Fire playback stopped event. */
		if(this.listener != null){
			this.listener.playbackStopped(this.track);
		}
	}
	
	/**
	 * Return the total length of the audio stream in seconds (if available).
	 * This information is loaded from the Spotify specific ogg header.
	 * 
	 * @return Length of audio stream in seconds or -1 if not available.
	 */
	public int length(){
		/* TODO: Remove hard-coded sample rate!? */
		if(this.spotifyOggHeader != null){
			return this.spotifyOggHeader.getSeconds(44100);
		}
		
		return -1;
	}
	
	/**
	 * Return the current playback position in seconds.
	 * 
	 * @return Position of playback in seconds.
	 */
	public int position(){
		return (int)(this.position / 1000000);
	}
	
	/**
	 * Get current volume value.
	 * 
	 * @return A value between 0.0 to 1.0.
	 */
	public float volume(){
		float gain;
		float volume;
		
		/* Get gain control. */
		FloatControl control = (FloatControl)this.audioLine.getControl(FloatControl.Type.MASTER_GAIN);
		
		/* Get gain and constrain it. */
		gain = MathUtilities.constrain(
			control.getValue(), control.getMinimum(), 0.0f
		);
		
		/* Calculate volume from gain. */
		if(gain == control.getMinimum()){
			volume = 0.0f;
		}
		else{
			volume = (float)Math.pow(10.0f, (gain / 20.0f) * 1.0f);
		}
		
		/* Return volume value. */
		return volume;
	}
	
	/**
	 * Set volume of audio line.
	 * 
	 * @param volume A value from 0.0 to 1.0.
	 */
	public void volume(float volume){
		float gain;
		
		/* Check arguments. */
		if(volume < 0.0f || volume > 1.0f){
			throw new IllegalArgumentException("Volume has to be a value from 0.0 to 1.0!");
		}
		
		/* Get gain control. */
		FloatControl control = (FloatControl)this.audioLine.getControl(FloatControl.Type.MASTER_GAIN);
		
		/* 
		 * Calculate gain from volume:
		 * 
		 * 100% volume =   0 dB
		 *  50% volume = - 6 dB
		 *  10% volume = -20 dB
		 *   1% volume = -40 dB
		 *   0% volume = min dB
		 */
		if(volume == 0.0){
			gain = control.getMinimum();
		}
		else{
			gain = 20.0f * (float)Math.log10(volume / 1.0f);
		}
		
		/* Set volume/gain (constrain it before). */
		control.setValue(MathUtilities.constrain(
			gain, control.getMinimum(), control.getMaximum()
		));
	}
	
	/**
	 * Close audio line and stream which will stop playing. Playing can't
	 * be resumed after that, use the "stop" method for that functionality.
	 */
	public void stop(){
		this.active = false;
		
		try{
			this.audioStream.close();
			this.audioLine.close();
		}
		catch(Exception e){
			/* Don't care. Catch IOException and NullPointerException. */
		}
		
		/* Fire playback stopped event. */
		if(this.listener != null){
			this.listener.playbackStopped(this.track);
		}
	}
	
	/* Called when a channel header is received. */
	public void channelHeader(Channel channel, byte[] header){
		/* Create buffer for this substream. */
		this.cacheData = new byte[this.streamLength];
		
		/* We didn't receive data yet. */
		this.receivedLength = 0;
	}
	
	/* Called when channel data is received. */
	public void channelData(Channel channel, byte[] data){
		/* Offsets needed for deinterleaving. */
		int off, w, x, y, z;
		
		/* Copy data to cache buffer. */
		for(int i = 0; i < data.length; i++){
			this.cacheData[this.receivedLength + i] = data[i];
		}
		
		/* Allocate space for ciphertext. */
		byte[] ciphertext = new byte[data.length];
		
		/* Deinterleave 4x256 byte blocks. */
		for(int block = 0; block < data.length / 1024; block++){
			off = block * 1024;
			w	= block * 1024 + 0 * 256;
			x	= block * 1024 + 1 * 256;
			y	= block * 1024 + 2 * 256;
			z	= block * 1024 + 3 * 256;
			
			for(int i = 0; i < 1024 && (block * 1024 + i) < data.length; i += 4){
				ciphertext[off++] = data[w++];
				ciphertext[off++] = data[x++];
				ciphertext[off++] = data[y++];
				ciphertext[off++] = data[z++];
			}
		}
		
		/* Decrypt data. */
		byte[] plaintext = this.cipher.update(ciphertext);
		
		/* Write data to output stream. */
		try{
			this.output.write(plaintext, 0, plaintext.length);
			this.output.flush();
		}
		catch(IOException e){
			/* Just don't care... */
		}
		
		this.receivedLength += data.length;
	}
	
	/* Called when a channel end is reached. */
	public void channelEnd(Channel channel){
		/* Create cache hash. */
		String hash = this.cache.hash(this.track, this.streamOffset, this.streamLength);
		
		/* Save to cache. */
		if(this.cache != null && !this.cache.contains("substream", hash)){
			this.cache.store("substream", hash, this.cacheData, this.receivedLength);
		}
		
		Channel.unregister(channel.getId());
		
		/* Loading complete. */
		this.loading = false;
		
		if(this.receivedLength < this.streamLength){
			try{
				this.output.close();
			}
			catch(IOException e){
				e.printStackTrace();
			}
		}
	}
	
	/* Called when a channel error occurs. */
	public void channelError(Channel channel){
		/* Just ignore channel errors. */
	}
}
