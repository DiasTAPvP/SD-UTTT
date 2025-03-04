package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.GameState;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;
import dk.easv.bll.move.Move;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class RandomMCTSTest implements IBot {
    final int moveTimeMs = 100;
    private String BOT_NAME = getClass().getSimpleName();
    private static final double UCT_EXPLORATION = 1.4142;
    private final Random rnd = new Random();

    // Hold the current game state for use in evaluateMove
    private IGameState currentGameState;

    // New helper: scan for an immediate winning move on the macroboard.
    private IMove findImmediateWinningMove(IGameState state) {
        for (IMove move : state.getField().getAvailableMoves()) {
            IGameState tempState = cloneState(state);
            updateGame(tempState, move, currentPlayer(tempState));

            // Check if the move wins at the sub-board level
            if (isLocalWin(tempState.getField().getBoard(), move, String.valueOf(currentPlayer(tempState)))) {
                // If it also leads to a macroboard win, prioritize immediately
                if (gameOver(tempState) == GameOverState.Win) {
                    return move; // Highest priority
                }
            }
        }
        return null;
    }

    // Check if a move results in a win on the sub-board
    private boolean isWinningMove(IGameState state, IMove move, String player) {
        String[][] board = Arrays.stream(state.getField().getBoard()).map(String[]::clone).toArray(String[][]::new);
        board[move.getX()][move.getY()] = player;

        int startX = move.getX() - (move.getX() % 3);
        if (board[startX][move.getY()].equals(player))
            if (board[startX][move.getY()].equals(board[startX + 1][move.getY()]) &&
                    board[startX + 1][move.getY()].equals(board[startX + 2][move.getY()]))
                return true;

        int startY = move.getY() - (move.getY() % 3);
        if (board[move.getX()][startY].equals(player))
            if (board[move.getX()][startY].equals(board[move.getX()][startY + 1]) &&
                    board[move.getX()][startY + 1].equals(board[move.getX()][startY + 2]))
                return true;

        if (board[startX][startY].equals(player))
            if (board[startX][startY].equals(board[startX + 1][startY + 1]) &&
                    board[startX + 1][startY + 1].equals(board[startX + 2][startY + 2]))
                return true;

        if (board[startX][startY + 2].equals(player))
            if (board[startX][startY + 2].equals(board[startX + 1][startY + 1]) &&
                    board[startX + 1][startY + 1].equals(board[startX + 2][startY]))
                return true;

        return false;
    }

    // Compile a list of all available winning moves
    private List<IMove> getWinningMoves(IGameState state) {
        String player = currentPlayer(state) == 0 ? "0" : "1";
        List<IMove> avail = state.getField().getAvailableMoves();
        List<IMove> winningMoves = new ArrayList<>();
        for (IMove move : avail) {
            if (isWinningMove(state, move, player))
                winningMoves.add(move);
        }
        return winningMoves;
    }

    @Override
    public IMove doMove(IGameState state) {
        // Save the state in the field for access in evaluateMove
        currentGameState = state;

        if (state.getMoveNumber() == 0) {
            IMove firstCenter = new Move(4, 4);
            if (state.getField().getAvailableMoves().contains(firstCenter)) {
                return firstCenter;
            }
        }

        // Check for an immediate win on the big board.
        IMove winMove = findImmediateWinningMove(state);
        if (winMove != null) {
            return winMove;
        }

        // Check for winning moves on the sub-boards.
        List<IMove> winMoves = getWinningMoves(state);
        if (!winMoves.isEmpty()) {
            return winMoves.get(0);
        }

        long endTime = System.currentTimeMillis() + moveTimeMs;
        Node root = new Node(null, cloneState(state), null);
        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;
        while (System.currentTimeMillis() < endTime) {
            Node promising = selectNode(root, alpha, beta);
            if (!isTerminal(promising.state)) {
                expandNode(promising);
            }
            Node toExplore = promising.children.isEmpty()
                    ? promising
                    : promising.children.get(rnd.nextInt(promising.children.size()));
            int result = simulate(toExplore.state);
            backpropagate(toExplore, result);
        }
        Node best = bestChild(root);
        return best.move != null ? best.move : randomMove(state);
    }

    // MCTS core methods

    private Node selectNode(Node node, double alpha, double beta) {
        Node current = node;
        while (!current.children.isEmpty()) {
            current = bestUCTChild(current.children, current.visits, current.state, alpha, beta);
            if (current.score >= beta) {
                return current;
            }
            alpha = Math.max(alpha, current.score);
        }
        return current;
    }

    private void parallelExpandNode(Node node) {
        List<IMove> moves = node.state.getField().getAvailableMoves();
        moves.parallelStream().forEach(move -> {
            IGameState newState = cloneState(node.state);
            updateGame(newState, move, currentPlayer(newState));
            synchronized (node.children) {
                node.children.add(new Node(move, newState, node));
            }
        });
    }

    private void expandNode(Node node) {
        List<IMove> moves = node.state.getField().getAvailableMoves();
        // Use evaluateMove to sort moves by a heuristic (here we use evaluateBoard on the resulting state)
        moves.sort((m1, m2) -> Integer.compare(evaluateMove(m2), evaluateMove(m1)));
        int limit = Math.min(moves.size(), 5); // Expand only the top 5 moves
        for (int i = 0; i < limit; i++) {
            IMove move = moves.get(i);
            IGameState newState = cloneState(node.state);
            updateGame(newState, move, currentPlayer(newState));
            node.children.add(new Node(move, newState, node));
        }
    }

    // In this example, evaluateMove is computed by applying the move heuristics via evaluateBoard.
    private int evaluateMove(IMove move) {
        // Use the current state stored in the field.
        IGameState tempState = cloneState(currentGameState);
        updateGame(tempState, move, currentPlayer(tempState));
        return evaluateBoard(tempState);
    }

    private boolean isWinningPattern(String[][] board, String player) {
        // Define winning patterns (e.g., rows, columns, diagonals)
        int[][][] patterns = {
                {{0, 0}, {0, 1}, {0, 2}},
                {{1, 0}, {1, 1}, {1, 2}},
                {{2, 0}, {2, 1}, {2, 2}},
                {{0, 0}, {1, 0}, {2, 0}},
                {{0, 1}, {1, 1}, {2, 1}},
                {{0, 2}, {1, 2}, {2, 2}},
                {{0, 0}, {1, 1}, {2, 2}},
                {{0, 2}, {1, 1}, {2, 0}}
        };

        for (int[][] pattern : patterns) {
            boolean win = true;
            for (int[] cell : pattern) {
                if (!board[cell[0]][cell[1]].equals(player)) {
                    win = false;
                    break;
                }
            }
            if (win) return true;
        }
        return false;
    }


    private int evaluateBoard(IGameState state) {
        int score = 0;
        String[][] board = state.getField().getBoard();
        String[][] macroBoard = state.getField().getMacroboard();

        // Check for winning patterns
        if (isWinningPattern(board, "0")) score += 100;
        if (isWinningPattern(board, "1")) score -= 100;

        // Evaluate positions
        int[][] positionValues = {
                {3, 2, 3, 2, 3, 2, 3, 2, 3},
                {2, 1, 2, 4, 1, 4, 2, 1, 2},
                {3, 2, 3, 2, 3, 2, 3, 2, 3},
                {2, 4, 2, 5, 3, 5, 2, 4, 2},
                {3, 1, 3, 3, 1, 3, 3, 1, 3},
                {2, 4, 2, 5, 3, 5, 2, 4, 2},
                {3, 2, 3, 2, 3, 2, 3, 2, 3},
                {2, 1, 2, 4, 2, 4, 2, 1, 2},
                {3, 2, 3, 2, 3, 2, 3, 2, 3}
        };

        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                if (board[x][y].equals("0")) {
                    score += positionValues[x][y];
                } else if (board[x][y].equals("1")) {
                    score -= positionValues[x][y];
                }
            }
        }

        // Bonus for controlling the center of the macroboard
        if (macroBoard != null) {
            String center = macroBoard[1][1];
            if (center.equals("0")) score += 50;
            else if (center.equals("1")) score -= 50;

            // Bonus for potential wins on the macroboard
            for (int i = 0; i < 3; i++) {
                if (macroBoard[i][0].equals("0") && macroBoard[i][1].equals("0") && macroBoard[i][2].equals(IField.AVAILABLE_FIELD)) score += 30;
                if (macroBoard[0][i].equals("0") && macroBoard[1][i].equals("0") && macroBoard[2][i].equals(IField.AVAILABLE_FIELD)) score += 30;
                if (macroBoard[i][0].equals("1") && macroBoard[i][1].equals("1") && macroBoard[i][2].equals(IField.AVAILABLE_FIELD)) score -= 30;
                if (macroBoard[0][i].equals("1") && macroBoard[1][i].equals("1") && macroBoard[2][i].equals(IField.AVAILABLE_FIELD)) score -= 30;
            }
            if (macroBoard[0][0].equals("0") && macroBoard[1][1].equals("0") && macroBoard[2][2].equals(IField.AVAILABLE_FIELD)) score += 30;
            if (macroBoard[0][2].equals("0") && macroBoard[1][1].equals("0") && macroBoard[2][0].equals(IField.AVAILABLE_FIELD)) score += 30;
            if (macroBoard[0][0].equals("1") && macroBoard[1][1].equals("1") && macroBoard[2][2].equals(IField.AVAILABLE_FIELD)) score -= 30;
            if (macroBoard[0][2].equals("1") && macroBoard[1][1].equals("1") && macroBoard[2][0].equals(IField.AVAILABLE_FIELD)) score -= 30;
        }

        return score;
    }


    private int simulate(IGameState simState) {
        IGameState temp = cloneState(simState);
        int totalScore = 0;
        while (!isTerminal(temp)) {
            List<IMove> moves = temp.getField().getAvailableMoves();
            if (moves.isEmpty()) break;
            IMove move = moves.get(rnd.nextInt(moves.size()));
            updateGame(temp, move, currentPlayer(temp));
            totalScore += evaluateBoard(temp);
        }
        GameOverState result = gameOver(temp);
        if (result == GameOverState.Win) {
            totalScore += 10;
        } else if (result == GameOverState.Tie) {
            totalScore += 5;
        }
        return totalScore;
    }

    private void backpropagate(Node node, int result) {
        Node current = node;
        while (current != null) {
            current.visits++;
            current.score += result;
            current = current.parent;
        }
    }

    private Node bestChild(Node node) {
        double bestScore = Double.NEGATIVE_INFINITY;
        Node best = null;
        for (Node child : node.children) {
            double avg = child.score / (child.visits + 1e-6);
            if (avg > bestScore) {
                bestScore = avg;
                best = child;
            }
        }
        return best;
    }

    private double getDynamicExplorationConstant(IGameState state) {
        int totalMoves = state.getMoveNumber();
        if (totalMoves < 20) {
            return 2.0;
        } else if (totalMoves < 40) {
            return 1.4142;
        } else {
            return 0.5;
        }
    }

    private Node bestUCTChild(List<Node> children, double parentVisits, IGameState state, double alpha, double beta) {
        Node best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        double explorationConstant = getDynamicExplorationConstant(state);
        for (Node c : children) {
            double heuristicValue = evaluateBoard(c.state);
            double uctValue = (c.score / (c.visits + 1e-6))
                    + explorationConstant * Math.sqrt(Math.log(parentVisits + 1e-6) / (c.visits + 1e-6))
                    + heuristicValue;
            if (uctValue > bestValue) {
                bestValue = uctValue;
                best = c;
            }
            if (uctValue >= beta) {
                return best;
            }
            alpha = Math.max(alpha, uctValue);
        }
        return best;
    }

    private IMove randomMove(IGameState state) {
        List<IMove> moves = state.getField().getAvailableMoves();
        return moves.get(rnd.nextInt(moves.size()));
    }

    private static boolean isTerminal(IGameState state) {
        return state.getField().getAvailableMoves().isEmpty() || gameOver(state) != GameOverState.Active;
    }

    // Modified gameOver now checks for a win on the macroboard.
    private static GameOverState gameOver(IGameState state) {
        String[][] macroBoard = state.getField().getMacroboard();
        if (isWin(macroBoard, "0") || isWin(macroBoard, "1")) {
            return GameOverState.Win;
        }
        // Tie detection can be expanded as needed.
        return GameOverState.Active;
    }

    // Check if the 3x3 macroboard has three in a row for the given player.
    private static boolean isWin(String[][] board, String player) {
        // check rows and columns
        for (int i = 0; i < 3; i++) {
            if (board[i][0].equals(player) && board[i][1].equals(player) && board[i][2].equals(player))
                return true;
            if (board[0][i].equals(player) && board[1][i].equals(player) && board[2][i].equals(player))
                return true;
        }
        // check diagonals
        if (board[0][0].equals(player) && board[1][1].equals(player) && board[2][2].equals(player))
            return true;
        if (board[0][2].equals(player) && board[1][1].equals(player) && board[2][0].equals(player))
            return true;
        return false;
    }

    // Modified updateGame also updates the macroboard.
    private void updateGame(IGameState state, IMove move, int player) {
        if (move == null) return;
        state.getField().getBoard()[move.getX()][move.getY()] = String.valueOf(player);
        state.setMoveNumber(state.getMoveNumber() + 1);
        updateMacroboard(state, move, player);
    }

    // Update the macroboard based on the move.
    private void updateMacroboard(IGameState state, IMove move, int player) {
        // Assume state.getField().getMacroboard() returns a 3x3 String array.
        String[][] macroBoard = state.getField().getMacroboard();
        int macroX = move.getX() / 3;
        int macroY = move.getY() / 3;
        // If the microboard corresponding to the move is not yet decided, check for a win.
        if (macroBoard[macroX][macroY].equals(IField.EMPTY_FIELD) ||
                macroBoard[macroX][macroY].equals(IField.AVAILABLE_FIELD)) {
            String[][] board = state.getField().getBoard();
            if (isLocalWin(board, move, String.valueOf(player))) {
                macroBoard[macroX][macroY] = String.valueOf(player);
            }
        }
        // Update available fields: if target macroboard cell is already won, set all empty cells to available.
        int xTrans = move.getX() % 3;
        int yTrans = move.getY() % 3;
        if (macroBoard[xTrans][yTrans].equals(IField.EMPTY_FIELD)) {
            macroBoard[xTrans][yTrans] = IField.AVAILABLE_FIELD;
        } else {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (macroBoard[i][j].equals(IField.EMPTY_FIELD))
                        macroBoard[i][j] = IField.AVAILABLE_FIELD;
                }
            }
        }
    }

    // Check a local 3x3 microboard win for the move.
    private boolean isLocalWin(String[][] board, IMove move, String player) {
        int localX = move.getX() % 3;
        int localY = move.getY() % 3;
        int startX = move.getX() - localX;
        int startY = move.getY() - localY;

        // check row
        boolean win = true;
        for (int i = startX; i < startX + 3; i++) {
            if (!board[i][move.getY()].equals(player)) {
                win = false;
                break;
            }
        }
        if (win) return true;
        // check column
        win = true;
        for (int i = startY; i < startY + 3; i++) {
            if (!board[move.getX()][i].equals(player)) {
                win = false;
                break;
            }
        }
        if (win) return true;
        // check diagonal
        if (localX == localY) {
            win = true;
            int y = startY;
            for (int i = startX; i < startX + 3; i++) {
                if (!board[i][y++].equals(player)) {
                    win = false;
                    break;
                }
            }
            if (win) return true;
        }
        // check anti-diagonal
        if (localX + localY == 2) {
            win = true;
            int y = startY + 2;
            for (int i = startX; i < startX + 3; i++) {
                if (!board[i][y--].equals(player)) {
                    win = false;
                    break;
                }
            }
            if (win) return true;
        }
        return false;
    }

    private int currentPlayer(IGameState state) {
        return state.getMoveNumber() % 2;
    }

    // This cloneState should perform a deep copy of the state.
    private IGameState cloneState(IGameState original) {
        // Assume that your concrete GameState class has a no-arg constructor.
        IGameState clone = new GameState();

        // Deep clone the board.
        String[][] originalBoard = original.getField().getBoard();
        String[][] cloneBoard = new String[originalBoard.length][];
        for (int i = 0; i < originalBoard.length; i++) {
            cloneBoard[i] = originalBoard[i].clone();
        }
        clone.getField().setBoard(cloneBoard);

        // Deep clone the macroboard if it exists.
        String[][] originalMacro = original.getField().getMacroboard();
        if (originalMacro != null) {
            String[][] cloneMacro = new String[originalMacro.length][];
            for (int i = 0; i < originalMacro.length; i++) {
                cloneMacro[i] = originalMacro[i].clone();
            }
            clone.getField().setMacroboard(cloneMacro);
        }

        // Copy the move number.
        clone.setMoveNumber(original.getMoveNumber());

        // Extend cloning for any other fields if needed.
        return clone;
    }

    @Override
    public String getBotName() {
        return BOT_NAME;
    }

    // Node class for MCTS
    private static class Node {
        IMove move;
        IGameState state;
        Node parent;
        List<Node> children = new ArrayList<>();
        double score = 0;
        double visits = 0;

        Node(IMove move, IGameState state, Node parent) {
            this.move = move;
            this.state = state;
            this.parent = parent;
        }
    }

    public enum GameOverState {
        Active,
        Win,
        Tie
    }
}