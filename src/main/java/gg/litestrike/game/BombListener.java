package gg.litestrike.game;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;

import static net.kyori.adventure.text.Component.text;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;

import io.papermc.paper.event.player.PlayerArmSwingEvent;

public class BombListener implements Listener {

	// 5 seconds to place
	final static int PLANT_TIME = (20 * 5);

	// 7 seconds to break
	final static int BREAK_TIME = (20 * 7);

	int is_planting = 0;

	int planting_counter = 0;
	int breaking_counter = 0;

	Block last_planting_block;

	BlockFace planting_face;

	List<MiningPlayer> mining_players = new ArrayList<>();

	BombModel bomb_model = new BombModel();

	Player last_planting_player;

	public BombListener() {
		new BukkitRunnable() {
			@Override
			public void run() {
				GameController gc = Litestrike.getInstance().game_controller;
				if (gc == null) {
					return;
				}

				if (is_planting > 0) {
					// as long as we are placing, advance timer
					planting_counter += 1;
					bomb_model.raise_bomb(planting_counter, planting_face);
					if (planting_counter == PLANT_TIME) {
						InvItemBomb pb = (InvItemBomb) Litestrike.getInstance().game_controller.bomb;
						pb.place_bomb(last_planting_block.getRelative(planting_face), bomb_model, planting_face);
						reset();
						Litestrike.getInstance().game_controller.getPlayerData(last_planting_player).add_plant();
					}
				} else {
					if (planting_counter > 1) {
						Block bomb_block = last_planting_block.getRelative(planting_face);
						SoundEffects.stop_planting(bomb_block.getX(), bomb_block.getY(), bomb_block.getZ());
						bomb_model.remove();
					}
					planting_counter = 0;
				}

				// always decrease timer
				is_planting -= 1;

				////// BReaking from here ///////

				List<MiningPlayer> remove_list = new ArrayList<>();
				for (MiningPlayer mp : mining_players) {
					mp.timer -= 1;
					if (mp.timer == 0) {
						remove_list.add(mp);
					}
				}
				mining_players.removeAll(remove_list);
				if (mining_players.size() == 0 && remove_list.size() != 0) {
					bomb_model.stop_bomb_mining();
				}

				if (mining_players.size() > 0) {
					breaking_counter += 1;
					if (breaking_counter == BREAK_TIME) {
						PlacedBomb b = (PlacedBomb) Litestrike.getInstance().game_controller.bomb;
						b.is_broken = true;
						Bukkit.getServer().sendMessage(text("ᴛʜᴇ ʙᴏᴍʙ ʜᴀꜱ ʙᴇᴇɴ ʙʀᴏᴋᴇɴ!").color(Litestrike.YELLOW));
						Litestrike.getInstance().game_controller.getPlayerData(mining_players.get(0).p).add_break();
						reset();
					}
				} else {
					breaking_counter = 0;
				}
			}
		}.runTaskTimer(Litestrike.getInstance(), 1, 1);
	}

	@EventHandler
	public void onBlockDamage(BlockDamageEvent e) {
		GameController gc = Litestrike.getInstance().game_controller;
		Material held_item = e.getPlayer().getInventory().getItemInMainHand().getType();
		if (gc == null ||
				gc.teams.get_team(e.getPlayer()) == Team.Placer ||
				!(gc.bomb instanceof PlacedBomb) ||
				!(held_item == Material.STONE_PICKAXE || held_item == Material.IRON_PICKAXE) ||
				e.getPlayer().getGameMode() != GameMode.SURVIVAL) {
			return;
		}

		if (is_player_mining(e.getPlayer())) {
			return;
		}

		// if not mining the bomb, return
		PlacedBomb pb = (PlacedBomb) gc.bomb;
		if (!pb.block.equals(e.getBlock())) {
			return;
		}

		mining_players.add(new MiningPlayer(e.getPlayer()));
		bomb_model.bomb_mining();
		SoundEffects.start_breaking(pb.block.getX(), pb.block.getY(), pb.block.getZ());
	}

	@EventHandler
	public void onDamageAbort(BlockDamageAbortEvent e) {
		remove_mining_player(e.getPlayer());
	}

	@EventHandler
	public void onSwingArm(PlayerArmSwingEvent e) {
		if (Litestrike.getInstance().game_controller == null) {
			return;
		}
		Bomb b = Litestrike.getInstance().game_controller.bomb;
		if (!(b instanceof PlacedBomb)) {
			return;
		}
		for (MiningPlayer mp : mining_players) {
			if (mp.p == e.getPlayer()) {
				mp.timer = 6 + ping_compensation_ticks(e.getPlayer());
				e.getPlayer().sendActionBar(text(renderBreakingProgress()));
				break;
			}
		}

		// check if add players to mining_players
		PlacedBomb pb = (PlacedBomb) b;
		Block target = e.getPlayer().getTargetBlockExact(5, FluidCollisionMode.NEVER);
		if (target == null) {
			return;
		}
		if (target.equals(pb.block)) {
			if (!(is_player_mining(e.getPlayer()))) {
				mining_players.add(new MiningPlayer(e.getPlayer()));
				bomb_model.bomb_mining();
				SoundEffects.start_breaking(pb.block.getX(), pb.block.getY(), pb.block.getZ());
			}
		} else {
			// arm swing while not on bomb
			remove_mining_player(e.getPlayer());
		}
	}

	private void remove_mining_player(Player p) {
		MiningPlayer to_remove = null;
		for (MiningPlayer mp : mining_players) {
			if (mp.p == p) {
				to_remove = mp;
				break;
			}
		}
		if (to_remove != null) {
			mining_players.remove(to_remove);
			if (mining_players.size() == 0) {
				bomb_model.stop_bomb_mining();
			}
		}
	}

	private boolean is_player_mining(Player p) {
		for (MiningPlayer mp : mining_players) {
			if (mp.p == p) {
				return true;
			}
		}
		return false;
	}

	@EventHandler
	public void onInteractPlacing(PlayerInteractEvent e) {

		if (e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) {
			e.setCancelled(true);
		}

		if (Litestrike.getInstance().game_controller == null) {
			return;
		}

		// uncancel the event when bomb is mined, so we get the BlockDamageEvent
		Bomb b = Litestrike.getInstance().game_controller.bomb;
		if (b instanceof PlacedBomb) {
			if (e.getClickedBlock() != null && e.getClickedBlock().equals(((PlacedBomb) b).block)) {
				e.setCancelled(false);
			}
		}

		if (e.getItem() == null ||
				!e.getItem().equals(Bomb.bomb_item()) ||
				e.getAction() != Action.RIGHT_CLICK_BLOCK ||
				e.getClickedBlock().getType() != Material.TERRACOTTA ||
				!(Litestrike.getInstance().game_controller.bomb instanceof InvItemBomb)) {
			return;
		}

		// sanity check
		if (Litestrike.getInstance().game_controller.teams.get_team(e.getPlayer()) == Team.Breaker) {
			Bukkit.getLogger().severe("ERROR: A Breaker planted the bomb!");
		}

		if (is_planting < 0) {
			Block lpb = e.getClickedBlock();
			SoundEffects.start_planting(lpb.getX(), lpb.getY(), lpb.getZ());
			bomb_model.spawn_model(lpb.getLocation());
		}
		is_planting = 7 + ping_compensation_ticks(e.getPlayer());

		e.getPlayer().sendActionBar(text(renderPlacingProgress()));
		last_planting_player = e.getPlayer();

		// if player starts looking at a different block, reset planting progress
		if (!e.getClickedBlock().equals(last_planting_block)) {
			reset();
			last_planting_block = e.getClickedBlock();
		}
		planting_face = e.getBlockFace();
	}

	@EventHandler
	public void onInvPickup(InventoryPickupItemEvent e) {
		// prevent bombitem from getting picked up by hopper
		if (e.getItem().getItemStack().equals(Bomb.bomb_item())) {
			e.setCancelled(true);
		}
	}

	@EventHandler
	public void onDrop(PlayerDropItemEvent e) {
		if (!e.getItemDrop().getItemStack().equals(Bomb.bomb_item())) {
			return;
		}
		reset();
		InvItemBomb ib = (InvItemBomb) Litestrike.getInstance().game_controller.bomb;
		ib.drop_bomb(e.getItemDrop());
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent e) {
		Bomb b = Litestrike.getInstance().game_controller.bomb;
		if (b instanceof InvItemBomb && ((InvItemBomb) b).p_inv == e.getPlayer().getInventory()) {
			Item i = Bukkit.getWorld("world").dropItem(e.getPlayer().getLocation(), Bomb.bomb_item());
			((InvItemBomb) b).drop_bomb(i);
		}
	}

	@EventHandler
	public void onPickup(EntityPickupItemEvent e) {
		if (!e.getItem().getItemStack().equals(Bomb.bomb_item())) {
			return;
		}
		e.setCancelled(true);
		if (e.getEntity() instanceof Player
				&& Litestrike.getInstance().game_controller.teams.get_team(e.getEntity().getName()) == Team.Placer) {
			// if it got picked up by a player and that player is placer, then proceed
			Player p = (Player) e.getEntity();
			Bomb.give_bomb(p.getInventory());
		}
	}

	// renders the breakingprogres for the action bar
	private String renderBreakingProgress() {
		double percentage = (double) breaking_counter / (double) BREAK_TIME;
		String s = "[";
		boolean b = false;
		for (double i = 0.1; i < 1; i += 0.1) {
			if (b) {
				s += " ";
				continue;
			}
			if (i < percentage) {
				s += "=";
				continue;
			}
			s += ">";
			b = true;
		}
		s += "]";
		return s;
	}

	private String renderPlacingProgress() {
		double percentage = (double) planting_counter / (double) PLANT_TIME;
		String s = "[";
		boolean b = false;
		for (double i = 0.1; i < 1; i += 0.1) {
			if (b) {
				s += " ";
				continue;
			}
			if (i < percentage) {
				s += "=";
				continue;
			}
			s += ">";
			b = true;
		}
		s += "]";
		return s;
	}

	private void reset() {
		is_planting = 0;
		planting_counter = 0;
		breaking_counter = 0;
		mining_players.clear();
		last_planting_block = null;
	}

	private int ping_compensation_ticks(Player p) {
		return Math.min(p.getPing() / 50, 10);
	}
}

class MiningPlayer {
	Player p;
	int timer = 12;

	public MiningPlayer(Player p) {
		this.p = p;
	}
}
