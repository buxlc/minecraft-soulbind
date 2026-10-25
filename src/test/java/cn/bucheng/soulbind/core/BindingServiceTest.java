package cn.bucheng.soulbind.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BindingServiceTest {
    private JavaPlugin plugin;
    private BindingService service;
    private YamlConfiguration config;
    private MockedStatic<Bukkit> bukkit;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("SoulBind");
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
        service = new BindingService(plugin);
    }

    @AfterEach void close() { if (bukkit != null) bukkit.close(); }

    @Test void generationRevocationRejectsOldCopies() throws Exception {
        UUID id = UUID.randomUUID();
        ItemStack original = item(id, 7);
        register(id, 7, "ACTIVE", original);
        assertTrue(service.isCurrent(original));
        assertFalse(service.isCurrent(item(id, 6)));
        assertFalse(service.isCurrent(item(id, 8)));
        assertFalse(service.isCurrent(item(UUID.randomUUID(), 7)));
        assertTrue(service.isBound(item(id, 6)), "Stale copies remain identifiable for removal");
    }

    @Test void warehouseAndDestroyedSnapshotsCannotAppearAsPhysicalItems() throws Exception {
        UUID id = UUID.randomUUID();
        ItemStack item = item(id, 0);
        register(id, 0, "VAULT", item);
        assertFalse(service.isCurrent(item));
        register(id, 0, "DESTROYED", item);
        assertFalse(service.isCurrent(item));
        register(id, 0, "UNBOUND", item);
        assertFalse(service.isCurrent(item));
    }

    @Test void recallFromAnUnloadedLocationRevokesItsOldGenerationAndStoresOnlyOneItem() throws Exception {
        UUID id = UUID.randomUUID();
        ItemStack original = item(id, 0), unloadedCopy = item(id, 0);
        register(id, 0, "ACTIVE", original);
        Player player = player(null);
        when(player.getInventory().firstEmpty()).thenReturn(-1);
        Path directory = Path.of("target", "core-test-data", UUID.randomUUID().toString()).toAbsolutePath();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Field loaded = BindingService.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(service, true);
        assertEquals(1, service.recall(player));
        assertFalse(service.isCurrent(unloadedCopy));
        assertEquals(1, service.vault(owner).size());
        assertEquals(1, service.recall(player));
        assertEquals(1, service.vault(owner).size(), "A full inventory must not duplicate an already warehoused item");
    }

    @Test void actualDestructionIsTombstonedButUnloadAndPickupRemainLive() throws Exception {
        for (EntityRemoveEvent.Cause cause : List.of(EntityRemoveEvent.Cause.DEATH,
                EntityRemoveEvent.Cause.DESPAWN, EntityRemoveEvent.Cause.EXPLODE,
                EntityRemoveEvent.Cause.OUT_OF_WORLD, EntityRemoveEvent.Cause.PLUGIN)) {
            UUID id = UUID.randomUUID();
            ItemStack bound = item(id, 0);
            register(id, 0, "ACTIVE", bound);
            Item entity = mock(Item.class);
            when(entity.getItemStack()).thenReturn(bound);
            service.onEntityRemove(new EntityRemoveEvent(entity, cause));
            assertFalse(service.isCurrent(bound), cause + " must permanently retire the snapshot");
        }
        for (EntityRemoveEvent.Cause cause : List.of(EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PICKUP)) {
            UUID id = UUID.randomUUID();
            ItemStack bound = item(id, 0);
            register(id, 0, "ACTIVE", bound);
            Item entity = mock(Item.class);
            when(entity.getItemStack()).thenReturn(bound);
            service.onEntityRemove(new EntityRemoveEvent(entity, cause));
            assertTrue(service.isCurrent(bound), cause + " must preserve the snapshot");
        }
    }

    @Test void keepInventoryDeathPreservesExactlyTheExistingItem() throws Exception {
        UUID id = UUID.randomUUID();
        ItemStack bound = item(id, 0);
        register(id, 0, "ACTIVE", bound);
        Player player = player(bound);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getKeepInventory()).thenReturn(true);
        service.onDeath(event);
        assertTrue(service.isCurrent(bound));
        assertTrue(service.vault(owner).isEmpty());
        verify(event, never()).getDrops();
    }

    @Test void ordinaryDeathRemovesDropAndStoresOneAuthoritativeItem() throws Exception {
        UUID id = UUID.randomUUID();
        ItemStack bound = item(id, 0), oldCopy = item(id, 0);
        register(id, 0, "ACTIVE", bound);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        Player player = player(bound);
        when(event.getEntity()).thenReturn(player);
        List<ItemStack> drops = new ArrayList<>(List.of(bound));
        when(event.getDrops()).thenReturn(drops);
        service.onDeath(event);
        assertTrue(drops.isEmpty());
        assertEquals(1, service.vault(owner).size(), "Inventory and drop list refer to the same physical item");
        assertFalse(service.isCurrent(oldCopy), "A corpse copy must become stale immediately");
    }

    @Test void derivedResultSlotsAreExcludedFromAuthoritativeSnapshots() {
        assertTrue(BindingService.isResultPreview("ANVIL", 2));
        assertFalse(BindingService.isResultPreview("ANVIL", 0));
        assertTrue(BindingService.isResultPreview("CRAFTING", 0));
        assertTrue(BindingService.isResultPreview("WORKBENCH", 0));
        assertFalse(BindingService.isResultPreview("CHEST", 2));
    }

    @Test void configuredDefaultsApplyAndFailedPolicySaveRollsBack() {
        config.set("defaults.allow-drop", true);
        config.set("defaults.auto-return", false);
        config.set("defaults.allow-others-pickup", true);
        PlayerSettings settings = service.settings(owner);
        assertTrue(settings.allowDrop());
        assertFalse(settings.autoReturn());
        assertTrue(settings.allowOthersPickup());
        assertThrows(IllegalStateException.class, () -> settings.setAllowDrop(false));
        assertTrue(settings.allowDrop(), "An unsaved policy change must not silently take effect");
    }

    @Test void unknownDatabaseVersionIsNeverOverwrittenOnFailedStartup() throws Exception {
        Path directory = Path.of("target", "core-test-data", UUID.randomUUID().toString()).toAbsolutePath();
        assertTrue(directory.startsWith(Path.of("target").toAbsolutePath()));
        Files.createDirectories(directory);
        Path data = directory.resolve("bindings.yml");
        String original = "schema-version: 999\nunknown: preserve-me\n";
        Files.writeString(data, original);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        assertThrows(IllegalStateException.class, service::load);
        service.shutdown();
        assertEquals(original, Files.readString(data));
    }

    private Player player(ItemStack bound) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getName()).thenReturn("Owner");
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{bound});
        when(inventory.getSize()).thenReturn(1);
        when(inventory.getItem(0)).thenReturn(bound);
        when(player.getInventory()).thenReturn(inventory);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        return player;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(UUID id, long generation, String state, ItemStack item) throws Exception {
        Class<?> entryClass = Class.forName(BindingService.class.getName() + "$Entry");
        Class<? extends Enum> stateClass = (Class<? extends Enum>) Class.forName(BindingService.class.getName() + "$State");
        Constructor<?> constructor = entryClass.getDeclaredConstructor(UUID.class, UUID.class, String.class,
                long.class, stateClass, ItemStack.class);
        constructor.setAccessible(true);
        Object entry = constructor.newInstance(id, owner, "Owner", generation, Enum.valueOf(stateClass, state), item);
        Field field = BindingService.class.getDeclaredField("entries");
        field.setAccessible(true);
        ((Map<UUID, Object>) field.get(service)).put(id, entry);
    }

    private ItemStack item(UUID id, long generation) {
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        doReturn(false).when(material).isAir();
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.clone()).thenReturn(item);
        when(item.serialize()).thenReturn(Map.of("type", "DIAMOND_SWORD", "amount", 1));
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> tags = new HashMap<>();
        tags.put(new NamespacedKey(plugin, "owner"), owner.toString());
        tags.put(new NamespacedKey(plugin, "item_id"), id.toString());
        tags.put(new NamespacedKey(plugin, "generation"), generation);
        tags.put(new NamespacedKey(plugin, "owner_name"), "Owner");
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(item.getItemMeta()).thenReturn(meta);
        when(data.has(any(NamespacedKey.class), any())).thenAnswer(call -> tags.containsKey(call.getArgument(0)));
        when(data.get(any(NamespacedKey.class), any())).thenAnswer(call -> tags.get(call.getArgument(0)));
        when(data.getOrDefault(any(NamespacedKey.class), any(), any())).thenAnswer(call -> tags.getOrDefault(call.getArgument(0), call.getArgument(2)));
        doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; }).when(data).set(any(), any(), any());
        doAnswer(call -> { tags.remove(call.getArgument(0)); return null; }).when(data).remove(any());
        return item;
    }
}
