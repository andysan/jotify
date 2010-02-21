package de.felixbruns.jotify.gui.listeners;

import java.util.List;

import de.felixbruns.jotify.api.media.Track;

public interface ControlListener {
	public void controlPlay();
	public void controlPause();
	public void controlPrevious();
	public void controlNext();
	public void controlVolume(float volume);
	public void controlSelect(Track track);
	public void controlSelect(List<Track> tracks);
	public void controlQueue(Track track);
}
