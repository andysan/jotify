package de.felixbruns.jotify.api.protocol;

public interface CommandListener {
	public void commandReceived(int command, byte[] payload);
}
