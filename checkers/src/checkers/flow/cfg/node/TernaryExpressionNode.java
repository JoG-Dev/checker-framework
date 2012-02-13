package checkers.flow.cfg.node;

import java.util.Collection;
import java.util.LinkedList;

import checkers.flow.util.HashCodeUtils;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.Tree.Kind;

/**
 * A node for a conditional expression:
 * 
 * <pre>
 *   <em>expression</em> ? <em>expression</em> : <em>expression</em>
 * </pre>
 * 
 * @author Stefan Heule
 * @author Charlie Garrett
 * 
 */
public class TernaryExpressionNode extends Node {

	protected BinaryTree tree;
	protected Node condition;
	protected Node thenOperand;
	protected Node elseOperand;

	public TernaryExpressionNode(BinaryTree tree, Node condition,
                                     Node thenOperand, Node elseOperand) {
		assert tree.getKind().equals(Kind.CONDITIONAL_EXPRESSION);
		this.tree = tree;
		this.condition = condition;
		this.thenOperand = thenOperand;
		this.elseOperand = elseOperand;
	}

	public Node getConditionOperand() {
		return condition;
	}

	public Node getThenOperand() {
		return thenOperand;
	}

	public Node getElseOperand() {
		return elseOperand;
	}

	@Override
	public BinaryTree getTree() {
		return tree;
	}

	@Override
	public <R, P> R accept(NodeVisitor<R, P> visitor, P p) {
		return visitor.visitTernaryExpression(this, p);
	}

	@Override
	public String toString() {
		return "(" + getConditionOperand() + " ? " + getThenOperand() +
			" : " + getElseOperand() + ")";
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || !(obj instanceof TernaryExpressionNode)) {
			return false;
		}
		TernaryExpressionNode other = (TernaryExpressionNode) obj;
		return getConditionOperand().equals(other.getConditionOperand())
				&& getThenOperand().equals(other.getThenOperand())
				&& getElseOperand().equals(other.getElseOperand());
	}

	@Override
	public int hashCode() {
		return HashCodeUtils.hash(getConditionOperand(), getThenOperand(),
			getElseOperand());
	}

	@Override
	public Collection<Node> getOperands() {
		LinkedList<Node> list = new LinkedList<Node>();
		list.add(getConditionOperand());
		list.add(getThenOperand());
		list.add(getElseOperand());
		return list;
	}

	@Override
	public boolean hasResult() {
		return true;
	}
}
