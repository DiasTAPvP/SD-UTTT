package dk.easv.bll.bot;

import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;
// import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dk.easv.bll.game.GameManager.GameOverState.Win;

public class FillerNameBot implements IBot {
    private static final String BOT_NAME="FillerBotName";
    private static final int SIMULATIONS = 1000;
    private final Random rnd = new Random();

    @Override
    public IMove doMove(IGameState state) {
        Node root = new Node(null, state, null);
        for (int i = 0; i < SIMULATIONS; i++) {
            Node promising = selectNode(root);
            if (!isTerminal(promising.gameState))
                expand(promising);

            Node nodeToExplore = promising;
            if (!promising.children.isEmpty())
                nodeToExplore = promising.getRandomChild();
            int result = simulate(nodeToExplore);
            bprop(nodeToExplore, result);
        }
        Node best = root.getChildWithBestScore();
        return best == null ? getRandomMove(state) : best.move;
    }

    private Node selectNode(Node root) {
        Node current = root;
        while (!current.children.isEmpty())
            current = current.bestUCT();
        return current;
    }

    private void expand(Node node) {
        for (IMove m : node.gameState.getField().getAvailableMoves()) {
            IGameState clone = cloneState(node.gameState, m);
            node.children.add(new Node(m, clone, node));
        }
    }

    private int simulate(Node node) {
        IGameState temp = cloneState(node.gameState, null);
        while (!isTerminal(temp)) {
            List<IMove> moves = temp.getField().getAvailableMoves();
            IMove nextMove = moves.get(rnd.nextInt(moves.size()));
            temp = cloneState(temp, nextMove);
        }
        return 1;
    }

    private void bprop(Node node, int result) {
        Node temp = node;
        while (temp != null) {
            temp.visits++;
            temp.score += result;
            temp = temp.getParent();
        }
    }

    private boolean isTerminal(IGameState state) {
        return state.getField().getAvailableMoves().isEmpty();
    }

    private IGameState cloneState(IGameState original, IMove move) {
        return original;
    }

    private IMove getRandomMove(IGameState state) {
        List<IMove> moves = state.getField().getAvailableMoves();
        return moves.isEmpty() ? null : moves.get(rnd.nextInt(moves.size()));
    }

    private class Node {
        IMove move;
        IGameState gameState;
        Node parent;
        List<Node> children = new ArrayList<>();
        double visits = 0;
        double score = 0;
        private final double c = 1.41;
        private Random nodeRnd = new Random();


        Node(IMove move, IGameState gameState, Node parent) {
            this.move = move;
            this.gameState = gameState;
            this.parent = parent;
        }

        Node getParent() {
            return parent;
        }

        Node getRandomChild() {
            return children.get(nodeRnd.nextInt(children.size()));
        }

        Node bestUCT() {
            Node best = null;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (Node child : children) {
                double uctValue = child.uct();
                if (uctValue > bestValue) {
                    bestValue = uctValue;
                    best = child;
                }
            }
            return best;
        }

        double uct() {
            if (visits == 0) return Double.MAX_VALUE;
            return (score / visits) + c * Math.sqrt(Math.log(parent.visits) / visits);
        }

        Node getChildWithBestScore() {
            Node best = null;
            double max = Double.NEGATIVE_INFINITY;
            for (Node child : children) {
                double ratio = child.score / child.visits;
                if (ratio > max) {
                    max = ratio;
                    best = child;
                }
            }
            return best;
        }

    }



    /**
     * IGameState
     * getAvailableMoves
     *
     *
     * @return
     */


    @Override
    public String getBotName() {
        return BOT_NAME;
    }
}
