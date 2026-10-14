package com.willfp.ecoskills.api.observation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.lang.ref.WeakReference;
/** Records only existing native magic writes, not a gameplay history. */
public final class ObservationSessions implements Listener {
 private static final Map<UUID,WeakReference<Player>> players=new HashMap<>();
 private static final Map<UUID,Set<String>> resources=new HashMap<>();
 public static void markMagicWrite(Player player,String type) {
  if(!Bukkit.isPrimaryThread()||Bukkit.getPlayer(player.getUniqueId())!=player)return;
  var previous=players.get(player.getUniqueId());
  if(previous==null||previous.get()!=player) { players.put(player.getUniqueId(),new WeakReference<>(player));resources.remove(player.getUniqueId()); }
  resources.computeIfAbsent(player.getUniqueId(),k->new HashSet<>()).add(type);
 }
 public static boolean hasCurrentMagic(Player player,String type) {
  var ref=players.get(player.getUniqueId());var found=resources.get(player.getUniqueId());
  return ref!=null&&ref.get()==player&&found!=null&&found.contains(type);
 }
 public static void clear() { players.clear();resources.clear(); }
 @EventHandler(priority=EventPriority.MONITOR) public void login(PlayerLoginEvent event) { remove(event.getPlayer().getUniqueId()); }
 @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { remove(event.getPlayer().getUniqueId()); }
 private static void remove(UUID uuid) { players.remove(uuid);resources.remove(uuid); }
}
