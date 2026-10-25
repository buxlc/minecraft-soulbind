package cn.bucheng.soulbind.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * One authoritative, persistent identity per bound item. A recalled item gets a
 * new generation; an old copy in an unloaded chunk is discarded on access.
 * All methods and Bukkit listeners must run on the server thread.
 */
public final class BindingService implements Listener {
    private enum State { ACTIVE, VAULT, DESTROYED, UNBOUND }

    private static final class Entry {
        final UUID id;
        final UUID owner;
        String ownerName;
        long generation;
        State state;
        ItemStack snapshot;

        Entry(UUID id, UUID owner, String name, long generation, State state, ItemStack snapshot) {
            this.id = id;
            this.owner = owner;
            this.ownerName = name;
            this.generation = generation;
            this.state = state;
            this.snapshot = snapshot;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey ownerKey, idKey, generationKey, nameKey, loreKey, oldMaxKey;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, PlayerSettings> policies = new HashMap<>();
    private final Map<UUID, List<ItemStack>> warehouses = new HashMap<>();
    private final Set<UUID> respawnDelivery = new HashSet<>();
    private final Set<UUID> suppressedRemovals = new HashSet<>();
    private BukkitTask maintenance;
    private boolean loaded;
    private boolean dirty;
    private String loreFormat = "§8[§d灵魂绑定§8] §7绑定者：§f{owner}";
    private BiFunction<String, Map<String, String>, String> messages = (key, values) -> key;

    public BindingService(JavaPlugin plugin) {
        this.plugin = plugin;
        ownerKey = new NamespacedKey(plugin, "owner");
        idKey = new NamespacedKey(plugin, "item_id");
        generationKey = new NamespacedKey(plugin, "generation");
        nameKey = new NamespacedKey(plugin, "owner_name");
        loreKey = new NamespacedKey(plugin, "lore_line");
        oldMaxKey = new NamespacedKey(plugin, "old_max_stack");
    }

    public void setMessages(BiFunction<String, Map<String, String>, String> messages) {
        this.messages = messages;
    }

    public void setLoreFormat(String format) {
        loreFormat = ChatColor.translateAlternateColorCodes('&', format);
    }

    public void load() {
        if (loaded) throw new IllegalStateException("Binding store is already loaded");
        Path data = plugin.getDataFolder().toPath().resolve("bindings.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (Files.exists(data)) yaml.load(data.toFile());
            if (Files.exists(data) && yaml.getInt("schema-version", -1) != 1)
                throw new IllegalStateException("Unsupported bindings.yml schema; original file preserved");
            ConfigurationSection section = yaml.getConfigurationSection("items");
            if (section != null) for (String key : section.getKeys(false)) {
                UUID id = UUID.fromString(key);
                ConfigurationSection item = section.getConfigurationSection(key);
                if (item == null) throw new IllegalStateException("Invalid item entry " + key);
                UUID owner = UUID.fromString(item.getString("owner", ""));
                State state = State.valueOf(item.getString("state", ""));
                ItemStack snapshot = item.getItemStack("snapshot");
                long generation = item.getLong("generation", -1L);
                if (generation < 0 || ((state == State.ACTIVE || state == State.VAULT) && empty(snapshot)))
                    throw new IllegalStateException("Invalid item snapshot " + key);
                entries.put(id, new Entry(id, owner, item.getString("owner-name", owner.toString()),
                        generation, state, snapshot));
            }
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) for (String key : players.getKeys(false)) {
                UUID id = UUID.fromString(key);
                ConfigurationSection player = players.getConfigurationSection(key);
                if (player == null) throw new IllegalStateException("Invalid player policy " + key);
                PlayerSettings settings = settings(id);
                settings.load(player.getBoolean("allow-drop", false), player.getBoolean("auto-return", true),
                        player.getBoolean("allow-others-pickup", false));
            }
            ConfigurationSection vaults = yaml.getConfigurationSection("warehouses");
            if (vaults != null) for (String key : vaults.getKeys(false)) {
                List<ItemStack> contents = new ArrayList<>();
                for (Object value : vaults.getList(key, List.of())) {
                    if (!(value instanceof ItemStack item) || empty(item))
                        throw new IllegalStateException("Invalid warehouse item " + key);
                    contents.add(item);
                }
                warehouses.put(UUID.fromString(key), contents);
            }
            loaded = true;
            long interval = Math.max(20L, plugin.getConfig().getLong("maintenance-ticks", 200L));
            maintenance = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) flushPlayer(player);
                if (dirty) saveQuietly();
            }, interval, interval);
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            entries.clear();
            policies.clear();
            warehouses.clear();
            throw new IllegalStateException("Cannot safely load bindings.yml; original file preserved", failure);
        }
    }

    public void save() {
        if (!loaded) throw new IllegalStateException("Binding store is not loaded");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        for (Entry entry : entries.values()) {
            String base = "items." + entry.id;
            yaml.set(base + ".owner", entry.owner.toString());
            yaml.set(base + ".owner-name", entry.ownerName);
            yaml.set(base + ".generation", entry.generation);
            yaml.set(base + ".state", entry.state.name());
            yaml.set(base + ".snapshot", entry.snapshot);
        }
        policies.forEach((owner, policy) -> {
            String base = "players." + owner;
            yaml.set(base + ".allow-drop", policy.allowDrop());
            yaml.set(base + ".auto-return", policy.autoReturn());
            yaml.set(base + ".allow-others-pickup", policy.allowOthersPickup());
        });
        warehouses.forEach((owner, contents) -> yaml.set("warehouses." + owner, contents));
        Path directory = plugin.getDataFolder().toPath();
        Path file = directory.resolve("bindings.yml"), temporary = directory.resolve("bindings.yml.tmp");
        try {
            Files.createDirectories(directory);
            yaml.save(temporary.toFile());
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist binding store", failure);
        }
    }

    public void shutdown() {
        if (maintenance != null) maintenance.cancel();
        if (!loaded) return;
        for (Player player : Bukkit.getOnlinePlayers()) flushPlayer(player);
        captureLoadedWorlds(false);
        save();
    }

    public ItemStack bind(Player owner, ItemStack single) {
        if (empty(single) || single.getAmount() != 1) throw new IllegalArgumentException("not-single");
        if (isBound(single)) throw new IllegalArgumentException("already-bound");
        if (containsNestedBinding(single)) throw new IllegalArgumentException("nested-bound");
        ItemStack result = single.clone();
        UUID id = UUID.randomUUID();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("not-single");
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        meta.getPersistentDataContainer().set(generationKey, PersistentDataType.LONG, 0L);
        meta.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, owner.getName());
        meta.getPersistentDataContainer().set(oldMaxKey, PersistentDataType.INTEGER,
                meta.hasMaxStackSize() ? meta.getMaxStackSize() : -1);
        meta.setMaxStackSize(1);
        result.setItemMeta(meta);
        refreshLore(result);
        entries.put(id, new Entry(id, owner.getUniqueId(), owner.getName(), 0L, State.ACTIVE, result.clone()));
        try { save(); }
        catch (RuntimeException failure) { entries.remove(id); throw failure; }
        return result;
    }

    public ItemStack unbind(ItemStack original) {
        if (!isBound(original)) throw new IllegalArgumentException("not-bound");
        Entry entry = currentEntry(original, true);
        if (entry == null) throw new IllegalArgumentException("stale");
        ItemStack result = original.clone();
        ItemMeta meta = result.getItemMeta();
        removeLore(meta);
        Integer previousMax = meta.getPersistentDataContainer().get(oldMaxKey, PersistentDataType.INTEGER);
        meta.setMaxStackSize(previousMax == null || previousMax < 0 ? null : previousMax);
        for (NamespacedKey key : List.of(ownerKey, idKey, generationKey, nameKey, loreKey, oldMaxKey))
            meta.getPersistentDataContainer().remove(key);
        result.setItemMeta(meta);
        State previous = entry.state;
        ItemStack snapshot = entry.snapshot;
        entry.state = State.UNBOUND;
        entry.snapshot = null;
        try { save(); }
        catch (RuntimeException failure) { entry.state = previous; entry.snapshot = snapshot; throw failure; }
        return result;
    }

    public boolean isBound(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    public UUID ownerOf(ItemStack item) { return uuidTag(item, ownerKey); }

    public String ownerName(ItemStack item) {
        UUID owner = ownerOf(item);
        if (owner == null) return "";
        Player online = Bukkit.getPlayer(owner);
        if (online != null) return online.getName();
        Entry entry = entries.get(uuidTag(item, idKey));
        if (entry != null) return entry.ownerName;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(nameKey, PersistentDataType.STRING, owner.toString());
    }

    public boolean isCurrent(ItemStack item) { return currentEntry(item, false) != null; }

    public PlayerSettings settings(UUID owner) {
        return policies.computeIfAbsent(owner, ignored -> {
            PlayerSettings policy = new PlayerSettings(this::save);
            policy.load(plugin.getConfig().getBoolean("defaults.allow-drop", false),
                    plugin.getConfig().getBoolean("defaults.auto-return", true),
                    plugin.getConfig().getBoolean("defaults.allow-others-pickup", false));
            return policy;
        });
    }

    public List<ItemStack> vault(UUID owner) {
        return warehouses.getOrDefault(owner, List.of()).stream().map(ItemStack::clone).toList();
    }

    public boolean withdraw(Player player, int index) {
        List<ItemStack> contents = warehouses.get(player.getUniqueId());
        if (contents == null || index < 0 || index >= contents.size()) return false;
        ItemStack item = contents.get(index);
        Entry entry = isBound(item) ? currentEntry(item, true) : null;
        if (isBound(item) && entry == null) {
            contents.remove(index);
            save();
            return false;
        }
        if (player.getInventory().firstEmpty() < 0) return false;
        int slot = player.getInventory().firstEmpty();
        if (entry != null) { refreshLore(item); entry.state = State.ACTIVE; entry.snapshot = item.clone(); }
        contents.remove(index);
        try { save(); }
        catch (RuntimeException failure) {
            contents.add(index, item);
            if (entry != null) entry.state = State.VAULT;
            throw failure;
        }
        player.getInventory().setItem(slot, item.clone());
        return true;
    }

    /** Caller relinquishes its source item before calling this method. */
    public void giveOrStore(UUID recipient, ItemStack source) {
        if (empty(source)) return;
        ItemStack item = source.clone();
        Entry entry = isBound(item) ? currentEntry(item, true) : null;
        if (isBound(item) && entry == null) return;
        if (entry != null) { refreshLore(item); entry.snapshot = item.clone(); }
        Player player = Bukkit.getPlayer(recipient);
        if (player != null && player.isOnline() && !player.isDead() && player.getInventory().firstEmpty() >= 0) {
            if (entry != null) entry.state = State.ACTIVE;
            player.getInventory().setItem(player.getInventory().firstEmpty(), item);
        } else {
            if (entry != null) entry.state = State.VAULT;
            warehouses.computeIfAbsent(recipient, ignored -> new ArrayList<>()).add(item);
        }
        dirty = true;
        save();
    }

    public void refreshLore(ItemStack item) {
        if (!isBound(item)) return;
        ItemMeta meta = item.getItemMeta();
        removeLore(meta);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String line = loreFormat.replace("{owner}", ownerName(item));
        lore.add(line);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(loreKey, PersistentDataType.STRING, line);
        meta.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, ownerName(item));
        item.setItemMeta(meta);
    }

    public void flushPlayer(Player player) {
        sanitize(player.getInventory());
        ItemStack cursor = player.getItemOnCursor();
        if (isBound(cursor) && !isCurrent(cursor)) player.setItemOnCursor(null);
        else capture(cursor);
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!isOwnMenu(top)) sanitize(top);
    }

    public int recall(Player player) {
        flushPlayer(player);
        captureLoadedWorlds(true);
        Set<UUID> held = new HashSet<>();
        for (ItemStack item : player.getInventory().getContents()) if (isCurrent(item)) held.add(uuidTag(item, idKey));
        if (isCurrent(player.getItemOnCursor())) held.add(uuidTag(player.getItemOnCursor(), idKey));
        List<Entry> missing = entries.values().stream()
                .filter(e -> e.owner.equals(player.getUniqueId()) && (e.state == State.ACTIVE || e.state == State.VAULT)
                        && !held.contains(e.id)).toList();
        int recalled = 0;
        for (Entry entry : missing) {
            if (empty(entry.snapshot)) continue;
            ItemStack snapshot = entry.snapshot.clone();
            purgeLoaded(entry.id);
            removeFromWarehouses(entry.id);
            entry.generation = Math.addExact(entry.generation, 1L);
            entry.state = State.ACTIVE;
            ItemMeta meta = snapshot.getItemMeta();
            meta.getPersistentDataContainer().set(generationKey, PersistentDataType.LONG, entry.generation);
            snapshot.setItemMeta(meta);
            entry.ownerName = player.getName();
            refreshLore(snapshot);
            entry.snapshot = snapshot.clone();
            // Persist the new authority in the warehouse before materializing it.
            entry.state = State.VAULT;
            warehouses.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).add(snapshot);
            recalled++;
        }
        if (recalled > 0) save();
        deliverWarehouse(player);
        return recalled;
    }

    private void removeLore(ItemMeta meta) {
        String line = meta.getPersistentDataContainer().get(loreKey, PersistentDataType.STRING);
        if (line != null && meta.hasLore()) {
            List<String> lore = new ArrayList<>(meta.getLore());
            // Remove only the last exact line this plugin previously appended.
            int index = lore.lastIndexOf(line);
            if (index >= 0) lore.remove(index);
            meta.setLore(lore.isEmpty() ? null : lore);
        }
    }

    private Entry currentEntry(ItemStack item, boolean includeVault) {
        if (!isBound(item) || item.getAmount() != 1) return null;
        UUID id = uuidTag(item, idKey), owner = ownerOf(item);
        if (id == null || owner == null) return null;
        Entry entry = entries.get(id);
        Long generation = item.getItemMeta().getPersistentDataContainer().get(generationKey, PersistentDataType.LONG);
        return entry != null && entry.owner.equals(owner) && generation != null && entry.generation == generation
                && (entry.state == State.ACTIVE || (includeVault && entry.state == State.VAULT)) ? entry : null;
    }

    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }

    private UUID uuidTag(ItemStack item, NamespacedKey key) {
        if (empty(item) || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    private void capture(ItemStack item) {
        Entry entry = currentEntry(item, false);
        if (entry == null) return;
        Player owner = Bukkit.getPlayer(entry.owner);
        if (owner != null) entry.ownerName = owner.getName();
        refreshLore(item);
        if (!item.equals(entry.snapshot)) { entry.snapshot = item.clone(); dirty = true; }
    }

    private void sanitize(Inventory inventory) {
        if (inventory == null || isOwnMenu(inventory)) return;
        Set<UUID> seen = new HashSet<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            // Anvil output is a preview of ingredient 0, not a second item.
            if (inventory.getType() != null && isResultPreview(inventory.getType().name(), slot)) continue;
            ItemStack item = inventory.getItem(slot);
            if (!isBound(item)) { sanitizeNested(item); continue; }
            if (!isCurrent(item) || !seen.add(uuidTag(item, idKey))) inventory.setItem(slot, null);
            else { capture(item); inventory.setItem(slot, item); }
        }
    }

    static boolean isResultPreview(String inventoryType, int slot) {
        return inventoryType.equals("ANVIL") && slot == 2
                || (inventoryType.equals("CRAFTING") || inventoryType.equals("WORKBENCH")) && slot == 0;
    }

    private boolean isOwnMenu(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder != null && holder.getClass().getName().startsWith("cn.bucheng.soulbind.gui.");
    }

    private boolean containsNestedBinding(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle)
            for (ItemStack child : bundle.getItems()) if (isBound(child) || containsNestedBinding(child)) return true;
        if (meta instanceof BlockStateMeta state && state.getBlockState() instanceof InventoryHolder holder)
            for (ItemStack child : holder.getInventory().getContents())
                if (isBound(child) || containsNestedBinding(child)) return true;
        if (meta instanceof CrossbowMeta crossbow)
            for (ItemStack child : crossbow.getChargedProjectiles())
                if (isBound(child) || containsNestedBinding(child)) return true;
        return false;
    }

    private void sanitizeNested(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        boolean changed = false;
        if (meta instanceof BundleMeta bundle) {
            List<ItemStack> contents = new ArrayList<>(bundle.getItems());
            changed = contents.removeIf(child -> isBound(child) && !isCurrent(child));
            for (ItemStack child : contents) { sanitizeNested(child); capture(child); }
            if (changed) bundle.setItems(contents);
        }
        if (meta instanceof BlockStateMeta state && state.getBlockState() instanceof InventoryHolder holder) {
            sanitize(holder.getInventory());
            state.setBlockState((BlockState) holder);
            changed = true;
        }
        if (meta instanceof CrossbowMeta crossbow) {
            List<ItemStack> contents = new ArrayList<>(crossbow.getChargedProjectiles());
            if (contents.removeIf(child -> isBound(child) && !isCurrent(child))) {
                crossbow.setChargedProjectiles(contents);
                changed = true;
            }
        }
        if (changed) item.setItemMeta(meta);
    }

    private void captureLoadedWorlds(boolean sanitize) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sanitize) flushPlayer(player);
            else for (ItemStack item : player.getInventory().getContents()) capture(item);
            if (sanitize) sanitize(player.getEnderChest());
            else for (ItemStack item : player.getEnderChest().getContents()) capture(item);
        }
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
            for (BlockState tile : chunk.getTileEntities()) if (tile instanceof InventoryHolder holder) {
                if (sanitize) sanitize(holder.getInventory());
                else for (ItemStack item : holder.getInventory().getContents()) capture(item);
            }
            for (Entity entity : chunk.getEntities()) checkEntity(entity, sanitize);
        }
    }

    private void checkEntity(Entity entity, boolean sanitize) {
        ItemStack item = entityItem(entity);
        if (isBound(item)) {
            if (sanitize && !isCurrent(item)) {
                if (entity instanceof ItemFrame frame) frame.setItem(null, false);
                else removeEntity(entity);
            }
            else {
                capture(item);
                if (entity instanceof Item ground) ground.setItemStack(item);
                else if (entity instanceof AbstractArrow arrow) arrow.setItem(item);
                else if (entity instanceof ItemFrame frame) frame.setItem(item, false);
            }
        }
        if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
            if (sanitize) sanitize(holder.getInventory());
            else for (ItemStack held : holder.getInventory().getContents()) capture(held);
        }
        if (entity instanceof LivingEntity living && !(entity instanceof Player) && living.getEquipment() != null) {
            var equipment = living.getEquipment();
            for (var slot : org.bukkit.inventory.EquipmentSlot.values()) {
                if (slot == org.bukkit.inventory.EquipmentSlot.BODY || slot == org.bukkit.inventory.EquipmentSlot.SADDLE) continue;
                ItemStack held = equipment.getItem(slot);
                if (sanitize && stale(held)) equipment.setItem(slot, null);
                else capture(held);
            }
        }
    }

    private ItemStack entityItem(Entity entity) {
        if (entity instanceof Item item) return item.getItemStack();
        if (entity instanceof AbstractArrow arrow) return arrow.getItem();
        if (entity instanceof ItemFrame frame) return frame.getItem();
        return null;
    }

    private void purgeLoaded(UUID id) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeFromInventory(player.getInventory(), id);
            removeFromInventory(player.getEnderChest(), id);
            if (!isOwnMenu(player.getOpenInventory().getTopInventory()))
                removeFromInventory(player.getOpenInventory().getTopInventory(), id);
            if (id.equals(uuidTag(player.getItemOnCursor(), idKey))) player.setItemOnCursor(null);
        }
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
            for (BlockState tile : chunk.getTileEntities())
                if (tile instanceof InventoryHolder holder) removeFromInventory(holder.getInventory(), id);
            for (Entity entity : chunk.getEntities()) {
                if (id.equals(uuidTag(entityItem(entity), idKey))) {
                    if (entity instanceof ItemFrame frame) frame.setItem(null, false);
                    else removeEntity(entity);
                }
                if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) removeFromInventory(holder.getInventory(), id);
                if (entity instanceof LivingEntity living && !(entity instanceof Player) && living.getEquipment() != null) {
                    var equipment = living.getEquipment();
                    for (var slot : org.bukkit.inventory.EquipmentSlot.values()) {
                        if (slot == org.bukkit.inventory.EquipmentSlot.BODY || slot == org.bukkit.inventory.EquipmentSlot.SADDLE) continue;
                        if (id.equals(uuidTag(equipment.getItem(slot), idKey))) equipment.setItem(slot, null);
                    }
                }
            }
        }
    }

    private void removeFromInventory(Inventory inventory, UUID id) {
        for (int slot = 0; slot < inventory.getSize(); slot++)
            if (id.equals(uuidTag(inventory.getItem(slot), idKey))) inventory.setItem(slot, null);
    }

    private void removeFromWarehouses(UUID id) {
        for (List<ItemStack> contents : warehouses.values())
            contents.removeIf(item -> id.equals(uuidTag(item, idKey)));
    }

    private void removeEntity(Entity entity) {
        suppressedRemovals.add(entity.getUniqueId());
        entity.remove();
        suppressedRemovals.remove(entity.getUniqueId());
    }

    private void destroy(ItemStack item) {
        Entry entry = currentEntry(item, false);
        if (entry == null) return;
        entry.state = State.DESTROYED;
        entry.snapshot = null;
        dirty = true;
        saveQuietly();
    }

    private void saveQuietly() {
        try { save(); }
        catch (RuntimeException failure) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Binding persistence failed", failure); }
    }

    private void tell(Player player, String key) { tell(player, key, Map.of()); }
    private void tell(Player player, String key, Map<String, String> arguments) {
        String value = messages.apply(key, arguments);
        if (value != null && !value.isBlank()) player.sendMessage(value);
    }

    private void nextTick(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { action.run(); }
            catch (RuntimeException failure) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Binding maintenance failed", failure); }
        });
    }

    private void deliverWarehouse(Player player) {
        List<ItemStack> contents = warehouses.get(player.getUniqueId());
        int count = 0;
        while (contents != null && !contents.isEmpty() && player.getInventory().firstEmpty() >= 0 && !player.isDead()) {
            if (!withdraw(player, 0)) break;
            count++;
        }
        if (count > 0) tell(player, "core.warehouse-returned", Map.of("count", Integer.toString(count)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        nextTick(() -> {
            if (!player.isOnline()) return;
            flushPlayer(player);
            if (settings(player.getUniqueId()).autoReturn()) {
                int count = recall(player);
                if (count > 0) tell(player, "core.recalled", Map.of("count", Integer.toString(count)));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { flushPlayer(event.getPlayer()); saveQuietly(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (event.getKeepInventory()) { flushPlayer(player); return; }
        Map<UUID, ItemStack> retained = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getContents())
            if (isCurrent(item)) retained.put(uuidTag(item, idKey), item.clone());
        if (isCurrent(player.getItemOnCursor())) {
            retained.put(uuidTag(player.getItemOnCursor(), idKey), player.getItemOnCursor().clone());
            player.setItemOnCursor(null);
        }
        for (ItemStack item : event.getDrops())
            if (isCurrent(item)) retained.put(uuidTag(item, idKey), item.clone());
        event.getDrops().removeIf(this::isBound);
        for (ItemStack item : retained.values()) {
            Entry entry = currentEntry(item, false);
            if (entry == null) continue;
            entry.generation++;
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(generationKey, PersistentDataType.LONG, entry.generation);
            item.setItemMeta(meta);
            entry.state = State.VAULT;
            entry.snapshot = item.clone();
            warehouses.computeIfAbsent(entry.owner, ignored -> new ArrayList<>()).add(item);
            if (entry.owner.equals(player.getUniqueId())) respawnDelivery.add(entry.owner);
            else {
                Player owner = Bukkit.getPlayer(entry.owner);
                if (owner != null) nextTick(() -> deliverWarehouse(owner));
            }
        }
        if (!retained.isEmpty()) saveQuietly();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (respawnDelivery.remove(event.getPlayer().getUniqueId())) nextTick(() -> deliverWarehouse(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (!isBound(item)) return;
        if (!isCurrent(item)) { event.setCancelled(true); nextTick(() -> flushPlayer(event.getPlayer())); return; }
        if (!settings(ownerOf(item)).allowDrop()) {
            event.setCancelled(true);
            tell(event.getPlayer(), "core.drop-denied");
        } else capture(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (!isBound(item)) return;
        if (!isCurrent(item)) { event.setCancelled(true); removeEntity(event.getItem()); return; }
        if (!(event.getEntity() instanceof Player player) || !canPickup(player, item)) {
            event.setCancelled(true);
            return;
        }
        capture(item);
        nextTick(() -> flushPlayer(player));
    }

    private boolean canPickup(Player player, ItemStack item) {
        return player.getUniqueId().equals(ownerOf(item)) || player.hasPermission("soulbind.admin")
                || settings(ownerOf(item)).allowOthersPickup();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        ItemStack item = event.getArrow().getItem();
        if (!isBound(item)) return;
        if (!isCurrent(item)) { event.setCancelled(true); removeEntity(event.getArrow()); }
        else if (!canPickup(event.getPlayer(), item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isBound(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (isBound(event.getEntity().getItemStack()) || isBound(event.getTarget().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) { checkEntity(event.getEntity(), true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow) capture(arrow.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (suppressedRemovals.contains(event.getEntity().getUniqueId())) return;
        if (!(event.getEntity() instanceof Item) && !(event.getEntity() instanceof AbstractArrow)) return;
        ItemStack item = entityItem(event.getEntity());
        if (!isCurrent(item)) return;
        if (Set.of(EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT,
                EntityRemoveEvent.Cause.PICKUP, EntityRemoveEvent.Cause.MERGE,
                EntityRemoveEvent.Cause.TRANSFORMATION).contains(event.getCause())) capture(item);
        else destroy(item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent || event.getEntity().getEquipment() == null) return;
        Set<UUID> dropped = new HashSet<>();
        for (ItemStack drop : event.getDrops()) if (isCurrent(drop)) dropped.add(uuidTag(drop, idKey));
        var equipment = event.getEntity().getEquipment();
        for (var slot : org.bukkit.inventory.EquipmentSlot.values()) {
            if (slot == org.bukkit.inventory.EquipmentSlot.BODY || slot == org.bukkit.inventory.EquipmentSlot.SADDLE) continue;
            ItemStack item = equipment.getItem(slot);
            if (isCurrent(item) && !dropped.contains(uuidTag(item, idKey))) destroy(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        nextTick(() -> {
            if (!event.getChunk().isLoaded()) return;
            for (BlockState tile : event.getChunk().getTileEntities())
                if (tile instanceof InventoryHolder holder) sanitize(holder.getInventory());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) checkEntity(entity, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (BlockState tile : event.getChunk().getTileEntities())
            if (tile instanceof InventoryHolder holder)
                for (ItemStack item : holder.getInventory().getContents()) capture(item);
        for (Entity entity : event.getChunk().getEntities()) checkEntity(entity, false);
        if (dirty) saveQuietly();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        sanitize(event.getInventory());
        if (event.getPlayer() instanceof Player player) flushPlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        sanitize(event.getInventory());
        if (event.getPlayer() instanceof Player player) flushPlayer(player);
        if (dirty) saveQuietly();
    }

    private boolean restricted(Inventory inventory) {
        if (inventory == null) return false;
        return switch (inventory.getType()) {
            case SHULKER_BOX, WORKBENCH, CRAFTING, FURNACE, BLAST_FURNACE, SMOKER, BREWING,
                 MERCHANT, SMITHING, SMITHING_NEW, GRINDSTONE, LOOM, CARTOGRAPHY, STONECUTTER,
                 BEACON, COMPOSTER, CRAFTER -> true;
            default -> false;
        };
    }

    private boolean bundle(ItemStack item) { return !empty(item) && item.getItemMeta() instanceof BundleMeta; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isOwnMenu(event.getView().getTopInventory())) return;
        ItemStack current = event.getCurrentItem(), cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
        if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) hotbar = player.getInventory().getItemInOffHand();
        if (stale(current) || stale(cursor) || stale(hotbar)) {
            event.setCancelled(true);
            nextTick(() -> flushPlayer(player));
            return;
        }
        boolean anyBound = isBound(current) || isBound(cursor) || isBound(hotbar);
        if (!anyBound) { nextTick(() -> flushPlayer(player)); return; }
        boolean interactingTop = event.getClickedInventory() == event.getView().getTopInventory() || event.isShiftClick();
        if ((restricted(event.getView().getTopInventory()) && interactingTop)
                || (bundle(current) && (isBound(cursor) || isBound(hotbar)))
                || (bundle(cursor) && isBound(current))) {
            event.setCancelled(true);
            tell(player, "core.processing-denied");
            return;
        }
        if (event.getView().getTopInventory().getType() == InventoryType.ANVIL && interactingTop) {
            // The first ingredient may be bound; the second ingredient must not be.
            if (event.getRawSlot() == 1 && (isBound(cursor) || isBound(hotbar))
                    || event.isShiftClick() && isBound(current)
                    || event.getRawSlot() == 2 && isBound(current) && !isCurrent(current)) {
                event.setCancelled(true);
                tell(player, "core.processing-denied");
                return;
            }
        }
        nextTick(() -> flushPlayer(player));
    }

    private boolean stale(ItemStack item) { return isBound(item) && !isCurrent(item); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isBound(event.getOldCursor())) return;
        if (stale(event.getOldCursor()) || (restricted(event.getView().getTopInventory())
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize()))
                || event.getView().getTopInventory().getType() == InventoryType.ANVIL && event.getRawSlots().contains(1)) {
            event.setCancelled(true);
            tell(player, "core.processing-denied");
        } else nextTick(() -> flushPlayer(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (!isBound(event.getItem())) return;
        if (!isCurrent(event.getItem()) || restricted(event.getDestination())) event.setCancelled(true);
        nextTick(() -> { sanitize(event.getSource()); sanitize(event.getDestination()); });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getItem(0), second = event.getInventory().getItem(1);
        if (isBound(second) || stale(first)) { event.setResult(null); return; }
        if (isBound(first) && !empty(event.getResult())) {
            ItemStack result = event.getResult();
            ItemMeta resultMeta = result.getItemMeta(), sourceMeta = first.getItemMeta();
            for (NamespacedKey key : List.of(ownerKey, idKey, nameKey, loreKey)) {
                String value = sourceMeta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                if (value != null) resultMeta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
            }
            resultMeta.getPersistentDataContainer().set(generationKey, PersistentDataType.LONG,
                    sourceMeta.getPersistentDataContainer().get(generationKey, PersistentDataType.LONG));
            resultMeta.getPersistentDataContainer().set(oldMaxKey, PersistentDataType.INTEGER,
                    sourceMeta.getPersistentDataContainer().getOrDefault(oldMaxKey, PersistentDataType.INTEGER, -1));
            resultMeta.setMaxStackSize(1);
            result.setItemMeta(resultMeta);
            refreshLore(result);
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem().clone();
        if (isCurrent(item)) nextTick(() -> destroyIfMissing(event.getPlayer(), item));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent event) { destroy(event.getBrokenItem()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isCurrent(event.getItemInHand()) && event.getPlayer().getGameMode() != GameMode.CREATIVE)
            destroy(event.getItemInHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!isBound(event.getItem())) return;
        // A dispenser can convert items into entities/blocks without preserving PDC.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (isBound(event.getConsumable())) {
            event.setCancelled(true);
            if (event.getEntity() instanceof Player player) tell(player, "core.processing-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (stale(item)) {
            event.setCancelled(true);
            nextTick(() -> flushPlayer(event.getPlayer()));
            return;
        }
        if (!event.getAction().name().startsWith("RIGHT_CLICK")) return;
        if (!empty(item) && item.getType() == Material.CROSSBOW) {
            for (ItemStack candidate : event.getPlayer().getInventory().getContents())
                if (isBound(candidate) && isAmmo(candidate.getType())) {
                    event.setCancelled(true);
                    tell(event.getPlayer(), "core.processing-denied");
                    return;
                }
        }
        if (isCurrent(item) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack snapshot = item.clone();
            nextTick(() -> destroyIfMissing(event.getPlayer(), snapshot));
        }
    }

    private boolean isAmmo(Material material) {
        return material == Material.ARROW || material == Material.SPECTRAL_ARROW
                || material == Material.TIPPED_ARROW || material == Material.FIREWORK_ROCKET;
    }

    private void destroyIfMissing(Player player, ItemStack item) {
        if (!isCurrent(item)) return;
        UUID id = uuidTag(item, idKey);
        for (ItemStack held : player.getInventory().getContents()) if (id.equals(uuidTag(held, idKey))) { capture(held); return; }
        if (id.equals(uuidTag(player.getItemOnCursor(), idKey))) return;
        for (Entity nearby : player.getWorld().getEntities()) {
            if (id.equals(uuidTag(entityItem(nearby), idKey))) { capture(entityItem(nearby)); return; }
            if (nearby instanceof LivingEntity living && !(nearby instanceof Player) && living.getEquipment() != null) {
                for (ItemStack equipment : living.getEquipment().getArmorContents())
                    if (id.equals(uuidTag(equipment, idKey))) { capture(equipment); return; }
                if (id.equals(uuidTag(living.getEquipment().getItemInMainHand(), idKey))
                        || id.equals(uuidTag(living.getEquipment().getItemInOffHand(), idKey))) return;
            }
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!isOwnMenu(top)) for (ItemStack held : top.getContents()) if (id.equals(uuidTag(held, idKey))) return;
        destroy(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (stale(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
            nextTick(() -> flushPlayer(event.getPlayer()));
        } else {
            ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
            if (isCurrent(item)) { ItemStack snapshot = item.clone(); nextTick(() -> destroyIfMissing(event.getPlayer(), snapshot)); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && stale(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            nextTick(() -> flushPlayer(player));
        }
    }
}
