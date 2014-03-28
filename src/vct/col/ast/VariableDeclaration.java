package vct.col.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import vct.col.rewrite.AbstractRewriter;
import vct.col.rewrite.MultiSubstitution;
import vct.util.ClassName;

/**
 * This class represents the usual shorthand for the declaration of multiple variables.
 * The class stores the base type of the operation and for each of the instances
 * it uses the name of the variable being declared as type base type of the declaration.
 * 
 * For example, <code>int x[],y;</code> is represented with base type <code>int</code>
 * and two declarations <code>x[] x</code> and <code>y y</code>.
 * 
 * @author Stefan Blom
 *
 */
public class VariableDeclaration extends ASTNode {

  /**
   * Base type for all declarations 
   */
  public final Type basetype;

  /**
   * Multiple variable declarations on top of the given base type.
   */
  private ArrayList<DeclarationStatement> vars=new ArrayList<DeclarationStatement>();
  
  /**
   * Create an empty list of variables.
   * 
   * @param basetype
   */
  public VariableDeclaration(Type basetype){
    this.basetype=basetype;
  }
  
  @Override
  protected <T> void accept_simple(ASTVisitor<T> visitor) {
    visitor.visit(this);
  }

  /**
   * Add a relative declaration.
   * 
   * @param decl
   */
  public void add(DeclarationStatement decl){
    vars.add(decl);
  }
  
  /**
   * Iterate over the variable declarations.
   */
  public Iterable<DeclarationStatement> get(){
    return vars;
  }

  /**
   * Flatten the list of relative declarations to a list of declarations
   * with the full type.
   *  
   * @return
   */
  public DeclarationStatement[] flatten() {
    ArrayList<DeclarationStatement> list=new ArrayList();
    Map<String,Type> map=new HashMap();
    AbstractRewriter rw=new MultiSubstitution(null,map);
    rw.create.setOrigin(getOrigin());
    for(DeclarationStatement d:vars){
      String name=d.getName();
      map.put(name,basetype);
      Type fulltype=rw.rewrite(d.getType());
      map.remove(name);
      DeclarationStatement tmp=rw.create.field_decl(name,fulltype, rw.copy_rw.rewrite(d.getInit()));
      if (isValidFlag(ASTFlags.STATIC)){
        tmp.setStatic(isStatic());
      }
      list.add(tmp);
    }
    return list.toArray(new DeclarationStatement[0]);
  }
}
