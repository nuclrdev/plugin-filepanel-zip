package dev.nuclr.plugin.event;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PluginClosePanelEvent {

	private final Object sourceProvider;

	public Map<String, Object> toEvent() {
		return Map.of("sourceProvider", sourceProvider);
	}

}
