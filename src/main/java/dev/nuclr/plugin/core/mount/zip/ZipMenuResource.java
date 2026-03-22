package dev.nuclr.plugin.core.mount.zip;

import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.event.PluginEvent;

public final class ZipMenuResource extends MenuResource {

	private final String name;
	private final String keyStroke;
	private final PluginEvent event;

	public ZipMenuResource(String name, String keyStroke, PluginEvent event) {
		this.name = name;
		this.keyStroke = keyStroke;
		this.event = event;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getKeyStroke() {
		return keyStroke;
	}

	@Override
	public PluginEvent getEvent() {
		return event;
	}
}
