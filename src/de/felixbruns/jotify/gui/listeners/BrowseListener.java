package de.felixbruns.jotify.gui.listeners;

import de.felixbruns.jotify.api.media.Album;
import de.felixbruns.jotify.api.media.Artist;
import de.felixbruns.jotify.api.media.Result;

public interface BrowseListener {
	public void browsedArtist(Artist artist);
	public void browsedAlbum(Album album);
	public void browsedTracks(Result result);
}
