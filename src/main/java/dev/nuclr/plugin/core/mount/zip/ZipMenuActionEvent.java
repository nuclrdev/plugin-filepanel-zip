package dev.nuclr.plugin.core.mount.zip;

import dev.nuclr.plugin.event.PluginEvent;

public final class ZipMenuActionEvent extends PluginEvent {

	private final String actionId;

	public ZipMenuActionEvent(String actionId) {
		this.actionId = actionId;
	}

	public String getActionId() {
		return actionId;
	}
}
