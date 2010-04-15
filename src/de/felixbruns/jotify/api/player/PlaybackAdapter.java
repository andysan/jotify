package de.felixbruns.jotify.api.player;

import de.felixbruns.jotify.api.media.Track;

public class PlaybackAdapter implements PlaybackListener {
	public void playbackStarted(Track track){}
	public void playbackStopped(Track track){}
	public void playbackResumed(Track track){}
	public void playbackPosition(Track track, int ms){}
	public void playbackFinished(Track track){}
}
