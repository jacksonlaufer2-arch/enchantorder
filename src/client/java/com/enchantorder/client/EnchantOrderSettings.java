package com.enchantorder.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's settings, saved in {@code config/enchantorder.properties}. */
public final class EnchantOrderSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("enchantorder");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("enchantorder.properties");
	private static final String AUTO_APPLY = "autoApply";

	private static Boolean autoApply;

	private EnchantOrderSettings() {
	}

	/** Whether the "Auto" box is ticked: the mod does the anvil steps for you when you click Apply. */
	public static boolean autoApply() {
		if (autoApply == null) {
			autoApply = Boolean.parseBoolean(load().getProperty(AUTO_APPLY, "false"));
		}
		return autoApply;
	}

	public static void setAutoApply(boolean value) {
		autoApply = value;
		Properties properties = load();
		properties.setProperty(AUTO_APPLY, Boolean.toString(value));
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			properties.store(writer, "Enchant Order settings");
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}

	private static Properties load() {
		Properties properties = new Properties();
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				properties.load(reader);
			} catch (IOException e) {
				LOGGER.warn("Couldn't read {}", FILE, e);
			}
		}
		return properties;
	}
}
