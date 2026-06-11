package com.lanard.serverlogin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType; 
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod("serverloginmod")
@EventBusSubscriber(modid = "serverloginmod")
public class ServerLoginMod {

    public static final String MODID = "serverloginmod";

    private static final Map<UUID, ItemStack[]> stashedInventories = new HashMap<>();
    private static final Map<UUID, Vec3> loginPositions = new HashMap<>();
    private static final Map<UUID, Long> joinTimes = new HashMap<>();

    public static final String[] SECURITY_QUESTIONS = {
        "What is the name of your first pet?",
        "What city were you born in?",
        "What is your mother's maiden name?",
        "What was the name of your first school?",
        "What is your favorite color?"
    };

    public ServerLoginMod() {
        AuthManager.load();
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String name = player.getScoreboardName();
            AuthManager.setLoggedIn(player.getUUID(), false);

            AuthManager.checkAndDemote(name);
            
            // NEW: Automatically promote them to approved if the server is open!
            AuthManager.checkAndPromote(name);

            int invSize = player.getInventory().getContainerSize();
            ItemStack[] stash = new ItemStack[invSize];
            for (int i = 0; i < invSize; i++) {
                stash[i] = player.getInventory().getItem(i).copy();
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
            stashedInventories.put(player.getUUID(), stash);
            loginPositions.put(player.getUUID(), player.position());
            joinTimes.put(player.getUUID(), System.currentTimeMillis());

            CommandSourceStack source = player.getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput();

            if (AuthManager.isApproved(name)) {
                player.sendSystemMessage(Component.literal("§eWelcome back! Please use: /login <password>"));
                player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " subtitle {\"text\":\"Please use: /login <password>\",\"color\":\"yellow\"}");
                player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " title {\"text\":\"Welcome Back!\",\"color\":\"gold\",\"bold\":true}");
            } else if (AuthManager.isPending(name)) {
                player.sendSystemMessage(Component.literal("§6Your registration is waiting for Admin approval."));
                player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " subtitle {\"text\":\"Waiting for Admin approval\",\"color\":\"gold\"}");
                player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " title {\"text\":\"Registration Pending\",\"color\":\"red\",\"bold\":true}");
            } else {
                player.sendSystemMessage(Component.literal("§cYou are not registered!"));
                player.sendSystemMessage(Component.literal("§bChoose a Security Question (Pick a number 1-5):"));
                for (int i = 0; i < SECURITY_QUESTIONS.length; i++) {
                    player.sendSystemMessage(Component.literal("§7" + (i + 1) + ". " + SECURITY_QUESTIONS[i]));
                }
                player.sendSystemMessage(Component.literal("§ePlease use: /register <password> <repeat> <question_number> <answer>"));
                player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " subtitle {\"text\":\"Check chat to register\",\"color\":\"yellow\"}");
                player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " title {\"text\":\"Welcome!\",\"color\":\"gold\",\"bold\":true}");
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!AuthManager.isLoggedIn(player.getUUID())) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 600, 255, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP, 600, 250, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 600, 0, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 600, 0, false, false, false));

                Vec3 lockedPos = loginPositions.get(player.getUUID());
                if (lockedPos != null && player.position().distanceToSqr(lockedPos) > 0.0001) {
                    player.teleportTo(lockedPos.x, lockedPos.y, lockedPos.z);
                }

                Long joinedAt = joinTimes.get(player.getUUID());
                if (joinedAt != null && System.currentTimeMillis() - joinedAt > 60000) {
                    player.connection.disconnect(Component.literal("§cLogin timeout! You took longer than 1 minute."));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTakeDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!AuthManager.isLoggedIn(player.getUUID())) event.setCanceled(true); 
        }
    }

    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!AuthManager.isLoggedIn(player.getUUID())) event.setCanceled(true); 
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!AuthManager.isLoggedIn(event.getEntity().getUUID())) event.setCanceled(true);
    }
    
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!AuthManager.isLoggedIn(event.getEntity().getUUID())) event.setCanceled(true);
    }
    
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!AuthManager.isLoggedIn(event.getEntity().getUUID())) event.setCanceled(true);
    }
    
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!AuthManager.isLoggedIn(event.getEntity().getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        if (!AuthManager.isLoggedIn(event.getPlayer().getUUID())) {
            event.setCanceled(true); 
            event.getPlayer().sendSystemMessage(Component.literal("§cYou must log in to use the chat!"));
        }
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        if (event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player) {
            if (!AuthManager.isLoggedIn(player.getUUID())) {
                String command = event.getParseResults().getReader().getString().toLowerCase().replace("/", "");
                if (!command.startsWith("login") && !command.startsWith("register") && !command.startsWith("resetpassword")) {
                    event.setCanceled(true); 
                    player.sendSystemMessage(Component.literal("§cYou must log in to use commands!"));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            loginPositions.remove(player.getUUID());
            joinTimes.remove(player.getUUID()); 
            ItemStack[] stash = stashedInventories.remove(player.getUUID());
            if (stash != null) {
                for (int i = 0; i < stash.length; i++) {
                    player.getInventory().setItem(i, stash[i]);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onCommandsRegister(RegisterCommandsEvent event) {
        
        event.getDispatcher().register(Commands.literal("register")
            .then(Commands.argument("password", StringArgumentType.word())
            .then(Commands.argument("repeat", StringArgumentType.word())
            .then(Commands.argument("question_number", IntegerArgumentType.integer(1, 5)) 
            .then(Commands.argument("security_answer", StringArgumentType.greedyString())
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                String name = player.getScoreboardName();
                String pass1 = StringArgumentType.getString(context, "password");
                String pass2 = StringArgumentType.getString(context, "repeat");
                int questionId = IntegerArgumentType.getInteger(context, "question_number");
                String answer = StringArgumentType.getString(context, "security_answer");

                if (AuthManager.isApproved(name) || AuthManager.isPending(name)) {
                    player.sendSystemMessage(Component.literal("§cYou already have an account or pending request."));
                    return 1;
                }

                if (!pass1.equals(pass2)) {
                    player.sendSystemMessage(Component.literal("§cPasswords do not match!"));
                    return 1;
                }

                if (AuthManager.isRequiresApproval()) {
                    AuthManager.addPending(name, pass1, questionId - 1, answer);
                    player.sendSystemMessage(Component.literal("§aRegistration sent! Waiting for admin approval."));
                    
                    CommandSourceStack source = player.getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput();
                    player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " subtitle {\"text\":\"Waiting for Admin approval\",\"color\":\"gold\"}");
                    player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " title {\"text\":\"Registration Sent\",\"color\":\"green\",\"bold\":true}");
                } else {
                    AuthManager.registerDirect(name, pass1, questionId - 1, answer);
                    player.sendSystemMessage(Component.literal("§aRegistration successful! Please use /login <password>"));
                    
                    CommandSourceStack source = player.getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput();
                    player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " subtitle {\"text\":\"Please use /login <password>\",\"color\":\"yellow\"}");
                    player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " title {\"text\":\"Registered!\",\"color\":\"green\",\"bold\":true}");
                }

                return 1;
            }))))));

        event.getDispatcher().register(Commands.literal("login")
            .then(Commands.argument("password", StringArgumentType.word())
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                String name = player.getScoreboardName();
                String inputPass = StringArgumentType.getString(context, "password");

                // NEW: Also check right when they try to log in, just in case they were already online when the server was opened!
                AuthManager.checkAndPromote(name);

                if (!AuthManager.isApproved(name)) {
                    player.sendSystemMessage(Component.literal("§cYou are not approved yet."));
                    return 1;
                }

                if (AuthManager.getPassword(name).equals(AuthManager.hashPassword(inputPass))) {
                    AuthManager.setLoggedIn(player.getUUID(), true);
                    player.sendSystemMessage(Component.literal("§aLogin successful!"));
                    
                    CommandSourceStack source = player.getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput();
                    player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " subtitle {\"text\":\"Enjoy your stay!\",\"color\":\"yellow\"}");
                    player.getServer().getCommands().performPrefixedCommand(source, "title " + name + " title {\"text\":\"Authenticated!\",\"color\":\"green\",\"bold\":true}");

                    player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    player.removeEffect(MobEffects.JUMP);
                    player.removeEffect(MobEffects.INVISIBILITY);
                    player.removeEffect(MobEffects.BLINDNESS);
                    loginPositions.remove(player.getUUID());
                    joinTimes.remove(player.getUUID()); 

                    ItemStack[] stash = stashedInventories.remove(player.getUUID());
                    if (stash != null) {
                        for (int i = 0; i < stash.length; i++) {
                            player.getInventory().setItem(i, stash[i]);
                        }
                    }
                } else {
                    player.sendSystemMessage(Component.literal("§4Incorrect password!"));
                    int qId = AuthManager.getSecurityQuestionId(name);
                    if (qId >= 0 && qId < SECURITY_QUESTIONS.length) {
                        player.sendSystemMessage(Component.literal("§bForgot it? Question: " + SECURITY_QUESTIONS[qId]));
                    }
                    player.sendSystemMessage(Component.literal("§eUse: /resetpassword <newpass> <repeat> <answer>"));
                }
                return 1;
            })));

        event.getDispatcher().register(Commands.literal("resetpassword")
            .then(Commands.argument("newpass", StringArgumentType.word())
            .then(Commands.argument("repeat", StringArgumentType.word())
            .then(Commands.argument("security_answer", StringArgumentType.greedyString())
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                String name = player.getScoreboardName();
                
                if (!AuthManager.isApproved(name)) {
                    player.sendSystemMessage(Component.literal("§cYou are not registered."));
                    return 1;
                }
                if (AuthManager.isLoggedIn(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§cYou are already logged in! Use /changepassword instead."));
                    return 1;
                }

                String pass1 = StringArgumentType.getString(context, "newpass");
                String pass2 = StringArgumentType.getString(context, "repeat");
                String answer = StringArgumentType.getString(context, "security_answer");

                if (!pass1.equals(pass2)) {
                    player.sendSystemMessage(Component.literal("§cNew passwords do not match!"));
                    return 1;
                }

                if (AuthManager.checkSecurityAnswer(name, answer)) {
                    AuthManager.updatePassword(name, pass1);
                    player.sendSystemMessage(Component.literal("§aPassword reset successfully! Please use /login <new_password>"));
                } else {
                    player.sendSystemMessage(Component.literal("§4Incorrect security answer!"));
                    int qId = AuthManager.getSecurityQuestionId(name);
                    if (qId >= 0 && qId < SECURITY_QUESTIONS.length) {
                        player.sendSystemMessage(Component.literal("§bHint: " + SECURITY_QUESTIONS[qId]));
                    }
                }
                return 1;
            })))));

        event.getDispatcher().register(Commands.literal("changepassword")
            .then(Commands.argument("newpass", StringArgumentType.word())
            .then(Commands.argument("repeat", StringArgumentType.word())
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                String name = player.getScoreboardName();
                
                if (!AuthManager.isLoggedIn(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§cYou must be logged in to change your password."));
                    return 1;
                }

                String pass1 = StringArgumentType.getString(context, "newpass");
                String pass2 = StringArgumentType.getString(context, "repeat");

                if (!pass1.equals(pass2)) {
                    player.sendSystemMessage(Component.literal("§cPasswords do not match!"));
                    return 1;
                }

                AuthManager.updatePassword(name, pass1);
                player.sendSystemMessage(Component.literal("§aPassword successfully changed!"));
                return 1;
            }))));

        event.getDispatcher().register(Commands.literal("unregister")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("nickname", StringArgumentType.word())
            .executes(context -> {
                CommandSourceStack source = context.getSource();
                String target = StringArgumentType.getString(context, "nickname");

                if (!AuthManager.isApproved(target)) {
                    source.sendFailure(Component.literal("Player " + target + " is not an approved user."));
                    return 1;
                }
                
                ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(target);
                if (targetPlayer != null) {
                    targetPlayer.connection.disconnect(Component.literal("§cYour account has been unregistered by an administrator."));
                }
                return 1;
            })));

        event.getDispatcher().register(Commands.literal("registerrequests")
            .requires(source -> source.hasPermission(2))
            .executes(context -> {
                CommandSourceStack source = context.getSource();
                if (AuthManager.getPendingRequests().isEmpty()) {
                    source.sendSuccess(() -> Component.literal("§aNo pending requests."), false);
                    return 1;
                }
                source.sendSuccess(() -> Component.literal("§bPending Requests:"), false);
                for (String req : AuthManager.getPendingRequests().keySet()) {
                    source.sendSuccess(() -> Component.literal("§e- " + req), false);
                }
                return 1;
            }));

        event.getDispatcher().register(Commands.literal("registerrequest")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("action", StringArgumentType.word())
            .then(Commands.argument("nicknames", StringArgumentType.greedyString())
            .executes(context -> {
                CommandSourceStack source = context.getSource();
                String action = StringArgumentType.getString(context, "action").toLowerCase();
                String targetsString = StringArgumentType.getString(context, "nicknames");
                
                if (!action.equals("approve") && !action.equals("decline")) {
                    source.sendFailure(Component.literal("Use 'approve' or 'decline'."));
                    return 1;
                }

                // Split the greedy string by spaces into an array of names
                String[] targets = targetsString.trim().split("\\s+");

                for (String target : targets) {
                    if (!AuthManager.isPending(target)) {
                        source.sendFailure(Component.literal("No pending request for " + target));
                        continue;
                    }

                    if (action.equals("approve")) {
                        AuthManager.approveRequest(target);
                        source.sendSuccess(() -> Component.literal("§aApproved " + target), true);
                        ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(target);
                        if (targetPlayer != null) {
                            targetPlayer.sendSystemMessage(Component.literal("§aYour account was approved! Use /login <password>"));
                            
                            CommandSourceStack targetSource = targetPlayer.getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput();
                            targetPlayer.getServer().getCommands().performPrefixedCommand(targetSource, "title " + target + " subtitle {\"text\":\"Please use /login <password>\",\"color\":\"yellow\"}");
                            targetPlayer.getServer().getCommands().performPrefixedCommand(targetSource, "title " + target + " title {\"text\":\"Account Approved!\",\"color\":\"green\",\"bold\":true}");
                        }
                    } else if (action.equals("decline")) {
                        AuthManager.declineRequest(target);
                        source.sendSuccess(() -> Component.literal("§cDeclined " + target), true);
                    }
                }
                return 1;
            }))));

        event.getDispatcher().register(Commands.literal("revokeapproval")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("nickname", StringArgumentType.word())
            .executes(context -> {
                CommandSourceStack source = context.getSource();
                String target = StringArgumentType.getString(context, "nickname");

                if (!AuthManager.isApproved(target)) {
                    source.sendFailure(Component.literal("§c" + target + " is not an approved player."));
                    return 1;
                }

                AuthManager.revokeApproval(target);
                source.sendSuccess(() -> Component.literal("§aRevoked approval for " + target + ". They are now pending."), true);

                ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(target);
                if (targetPlayer != null) {
                    targetPlayer.connection.disconnect(Component.literal("§cYour approval status was revoked by an administrator."));
                }
                return 1;
            })));

        event.getDispatcher().register(Commands.literal("requireapproval")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("value", BoolArgumentType.bool())
            .executes(context -> {
                boolean val = BoolArgumentType.getBool(context, "value");
                AuthManager.setRequiresApproval(val, false);
                context.getSource().sendSuccess(() -> Component.literal("§aAdmin approval required: §e" + val + " §7(Reapproval: false)"), true);
                return 1;
            })
            .then(Commands.argument("require_reapproval", BoolArgumentType.bool())
            .executes(context -> {
                boolean val = BoolArgumentType.getBool(context, "value");
                boolean reApp = BoolArgumentType.getBool(context, "require_reapproval");
                
                if (!val && reApp) {
                    context.getSource().sendFailure(Component.literal("You cannot require re-approval while initial approval is turned off!"));
                    return 1;
                }

                AuthManager.setRequiresApproval(val, reApp);
                context.getSource().sendSuccess(() -> Component.literal("§aAdmin approval required: §e" + val + " §7| Reapproval for open-server users: §e" + reApp), true);
                return 1;
            }))));

        event.getDispatcher().register(Commands.literal("registeredusers")
            .requires(source -> source.hasPermission(2))
            .executes(context -> {
                CommandSourceStack source = context.getSource();
                if (AuthManager.getApprovedUsers().isEmpty()) {
                    source.sendSuccess(() -> Component.literal("§eNo users are currently registered."), false);
                    return 1;
                }
                source.sendSuccess(() -> Component.literal("§b--- Registered Users ---"), false);
                for (Map.Entry<String, AuthManager.UserProfile> entry : AuthManager.getApprovedUsers().entrySet()) {
                    String user = entry.getKey();
                    boolean approvedByAdmin = entry.getValue().wasAdminApproved;
                    String status = approvedByAdmin ? "§aYes" : "§cNo";
                    source.sendSuccess(() -> Component.literal("§a- " + user + " §7(Admin Approved: " + status + ")"), false);
                }
                return 1;
            }));
    }
}