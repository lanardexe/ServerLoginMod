package com.lanard.serverlogin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuthManager {

    public static class UserProfile {
        public String passwordHash;
        public int securityQuestionId;
        public String securityAnswerHash;
        public boolean wasAdminApproved; 

        public UserProfile(String passwordHash, int securityQuestionId, String securityAnswerHash, boolean wasAdminApproved) {
            this.passwordHash = passwordHash;
            this.securityQuestionId = securityQuestionId;
            this.securityAnswerHash = securityAnswerHash;
            this.wasAdminApproved = wasAdminApproved;
        }
    }

    private static Map<String, UserProfile> pendingRequests = new HashMap<>();
    private static Map<String, UserProfile> approvedUsers = new HashMap<>();
    
    private static boolean requiresApproval = true;
    private static boolean requiresReapproval = false; 

    private static final Map<UUID, Boolean> loggedInSession = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DATA_FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "loginmod_auth.json");

    private static class AuthData {
        Boolean requiresApproval = true; 
        Boolean requiresReapproval = false; 
        Map<String, UserProfile> pending = new HashMap<>();
        Map<String, UserProfile> approved = new HashMap<>();
    }

    public static String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash string", e);
        }
    }

    public static String hashPassword(String password) {
        return hashString(password);
    }

    public static void load() {
        if (!DATA_FILE.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(DATA_FILE)) {
            Type type = new TypeToken<AuthData>() {}.getType();
            AuthData data = GSON.fromJson(reader, type);
            if (data != null) {
                if (data.requiresApproval != null) requiresApproval = data.requiresApproval;
                if (data.requiresReapproval != null) requiresReapproval = data.requiresReapproval;
                if (data.pending != null) pendingRequests = data.pending;
                if (data.approved != null) approvedUsers = data.approved;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        AuthData data = new AuthData();
        data.requiresApproval = requiresApproval;
        data.requiresReapproval = requiresReapproval;
        data.pending = pendingRequests;
        data.approved = approvedUsers;

        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean isRequiresApproval() { return requiresApproval; }
    public static boolean isRequiresReapproval() { return requiresReapproval; } 
    
    public static void setRequiresApproval(boolean reqApprove, boolean reqReapprove) {
        requiresApproval = reqApprove;
        requiresReapproval = reqReapprove;
        save();
    }

    public static boolean isApproved(String username) { return approvedUsers.containsKey(username); }
    public static boolean isPending(String username) { return pendingRequests.containsKey(username); }
    
    public static String getPassword(String username) { 
        return approvedUsers.containsKey(username) ? approvedUsers.get(username).passwordHash : null; 
    }

    public static int getSecurityQuestionId(String username) {
        return approvedUsers.containsKey(username) ? approvedUsers.get(username).securityQuestionId : -1;
    }

    public static boolean checkSecurityAnswer(String username, String rawAnswer) {
        if (!approvedUsers.containsKey(username)) return false;
        String hashedInput = hashString(rawAnswer.toLowerCase().trim());
        return approvedUsers.get(username).securityAnswerHash.equals(hashedInput);
    }
    
    public static void registerDirect(String username, String rawPassword, int questionId, String rawAnswer) {
        UserProfile profile = new UserProfile(
            hashPassword(rawPassword),
            questionId,
            hashString(rawAnswer.toLowerCase().trim()),
            false 
        );
        approvedUsers.put(username, profile);
        save();
    }

    public static void addPending(String username, String rawPassword, int questionId, String rawAnswer) {
        UserProfile profile = new UserProfile(
            hashPassword(rawPassword),
            questionId,
            hashString(rawAnswer.toLowerCase().trim()),
            false 
        );
        pendingRequests.put(username, profile);
        save();
    }

    public static void approveRequest(String username) {
        if (pendingRequests.containsKey(username)) {
            UserProfile profile = pendingRequests.remove(username);
            profile.wasAdminApproved = true; 
            approvedUsers.put(username, profile);
            save();
        }
    }

    public static void declineRequest(String username) {
        pendingRequests.remove(username);
        save();
    }

    public static void updatePassword(String username, String newRawPassword) {
        if (approvedUsers.containsKey(username)) {
            approvedUsers.get(username).passwordHash = hashPassword(newRawPassword);
            save();
        }
    }

    // NEW: Method to revoke a player's approval and send them back to pending
    public static void revokeApproval(String username) {
        if (approvedUsers.containsKey(username)) {
            UserProfile profile = approvedUsers.remove(username);
            profile.wasAdminApproved = false; // Reset their admin approval status
            pendingRequests.put(username, profile);
            save();
        }
    }

    public static Map<String, UserProfile> getPendingRequests() { return pendingRequests; }
    
    public static Map<String, UserProfile> getApprovedUsers() { return approvedUsers; }

    public static void checkAndDemote(String username) {
        if (requiresReapproval && approvedUsers.containsKey(username)) {
            UserProfile profile = approvedUsers.get(username);
            if (!profile.wasAdminApproved) {
                pendingRequests.put(username, approvedUsers.remove(username));
                save();
            }
        }
    }

    public static void checkAndPromote(String username) {
        if (!requiresApproval && pendingRequests.containsKey(username)) {
            UserProfile profile = pendingRequests.remove(username);
            approvedUsers.put(username, profile);
            save();
        }
    }

    public static boolean isLoggedIn(UUID uuid) {
        return loggedInSession.getOrDefault(uuid, false);
    }

    public static void setLoggedIn(UUID uuid, boolean status) {
        loggedInSession.put(uuid, status);
    }

    public static void clearSession(UUID uuid) {
        loggedInSession.remove(uuid);
    }
}