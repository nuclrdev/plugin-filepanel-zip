package dev.nuclr.plugin.event;

import java.util.Map;

import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PluginOpenItemEvent {

	private final Object sourceProvider;
	private final NuclrResourcePath resource;

	public Map<String, Object> toEventData() {
		return Map.of(
			"sourceProvider", sourceProvider,
			"resource", resource
		);
	}

}
