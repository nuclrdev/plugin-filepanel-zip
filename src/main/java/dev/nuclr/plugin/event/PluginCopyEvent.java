package dev.nuclr.plugin.event;

import java.util.List;
import java.util.Map;

import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PluginCopyEvent {

	private final Object sourceProvider;
	private final List<NuclrResourcePath> resources;
	
	public Map<String, Object> toEvent() {
		return Map.of(
			"sourceProvider", sourceProvider,
			"resources", resources
		);
	}
}
