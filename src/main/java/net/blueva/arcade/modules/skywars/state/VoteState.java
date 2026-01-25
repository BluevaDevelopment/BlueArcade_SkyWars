package net.blueva.arcade.modules.skywars.state;

import net.blueva.arcade.modules.skywars.support.vote.VoteCategory;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoteState {

    private final Map<VoteCategory, Map<String, Integer>> votes = new EnumMap<>(VoteCategory.class);
    private final Map<UUID, Map<VoteCategory, String>> playerVotes = new ConcurrentHashMap<>();
    private final Map<VoteCategory, String> defaults = new EnumMap<>(VoteCategory.class);

    public VoteState(Map<VoteCategory, String> defaults) {
        if (defaults != null) {
            this.defaults.putAll(defaults);
        }
    }

    public void castVote(UUID playerId, VoteCategory category, String option) {
        if (playerId == null || category == null || option == null) {
            return;
        }

        Map<VoteCategory, String> playerMap = playerVotes.computeIfAbsent(playerId, ignored -> new EnumMap<>(VoteCategory.class));
        String previous = playerMap.put(category, option);

        if (previous != null && previous.equals(option)) {
            return;
        }

        Map<String, Integer> categoryVotes = votes.computeIfAbsent(category, ignored -> new ConcurrentHashMap<>());
        if (previous != null) {
            categoryVotes.computeIfPresent(previous, (key, value) -> Math.max(0, value - 1));
        }
        categoryVotes.merge(option, 1, Integer::sum);
    }

    public int getVotes(VoteCategory category, String option) {
        if (category == null || option == null) {
            return 0;
        }
        Map<String, Integer> categoryVotes = votes.get(category);
        if (categoryVotes == null) {
            return 0;
        }
        return categoryVotes.getOrDefault(option, 0);
    }

    public String getPlayerVote(UUID playerId, VoteCategory category) {
        if (playerId == null || category == null) {
            return null;
        }
        Map<VoteCategory, String> playerMap = playerVotes.get(playerId);
        if (playerMap == null) {
            return null;
        }
        return playerMap.get(category);
    }

    public void clearPlayerVotes(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Map<VoteCategory, String> playerMap = playerVotes.remove(playerId);
        if (playerMap == null || playerMap.isEmpty()) {
            return;
        }
        for (Map.Entry<VoteCategory, String> entry : playerMap.entrySet()) {
            VoteCategory category = entry.getKey();
            String option = entry.getValue();
            if (category == null || option == null) {
                continue;
            }
            Map<String, Integer> categoryVotes = votes.get(category);
            if (categoryVotes == null) {
                continue;
            }
            categoryVotes.computeIfPresent(option, (key, value) -> {
                int nextValue = value - 1;
                return nextValue > 0 ? nextValue : null;
            });
            if (categoryVotes.isEmpty()) {
                votes.remove(category);
            }
        }
    }

    public void clearAll() {
        playerVotes.clear();
        votes.clear();
    }

    public String resolveWinner(VoteCategory category) {
        if (category == null) {
            return null;
        }

        Map<String, Integer> categoryVotes = votes.get(category);
        if (categoryVotes == null || categoryVotes.isEmpty()) {
            return defaults.get(category);
        }

        int maxVotes = -1;
        String winningOption = null;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : categoryVotes.entrySet()) {
            int count = entry.getValue();
            if (count > maxVotes) {
                maxVotes = count;
                winningOption = entry.getKey();
                tie = false;
            } else if (count == maxVotes) {
                tie = true;
            }
        }

        if (winningOption == null) {
            return defaults.get(category);
        }

        if (tie) {
            String fallback = defaults.get(category);
            return fallback != null ? fallback : winningOption;
        }

        return winningOption;
    }

    public boolean hasVotes(VoteCategory category) {
        if (category == null) {
            return false;
        }
        Map<String, Integer> categoryVotes = votes.get(category);
        if (categoryVotes == null || categoryVotes.isEmpty()) {
            return false;
        }
        for (int count : categoryVotes.values()) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }
}
