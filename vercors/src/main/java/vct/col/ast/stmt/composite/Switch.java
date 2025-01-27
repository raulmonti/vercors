package vct.col.ast.stmt.composite;

import vct.col.util.ASTMapping;
import vct.col.util.ASTMapping1;
import vct.col.ast.generic.ASTNode;
import vct.col.ast.util.ASTVisitor;

import java.util.ArrayList;
import java.util.Arrays;

import static hre.lang.System.Debug;

public class Switch extends ASTNode {

  public static class Case {
    public final ArrayList<ASTNode> cases=new ArrayList<ASTNode>();
    public final ArrayList<ASTNode> stats=new ArrayList<ASTNode>();
  }
  
  @Override
  public <R,A> R accept_simple(ASTMapping1<R,A> map, A arg){
    return map.map(this,arg);
  }

  
  @Override
  public <T> void accept_simple(ASTVisitor<T> visitor){
    try {
      visitor.visit(this);
    } catch (Throwable t){
      if (thrown.get()!=t){
        Debug("Triggered by %s:",getOrigin());
        thrown.set(t);
     }
      throw t;
    }
  }
  
  @Override
  public <T> T accept_simple(ASTMapping<T> map){
    try {
      return map.map(this);
    } catch (Throwable t){
      if (thrown.get()!=t){
        Debug("Triggered by %s:",getOrigin());
        thrown.set(t);
    }
      throw t;
    }
  }
 
  public final ASTNode expr;
  public final Case cases[];
  
  public Switch(ASTNode expr,Case ... cases){
    this.expr=expr;
    this.cases=Arrays.copyOf(cases,cases.length);
  }

}
