package vct.col.rewrite;

import vct.col.ast.ASTNode;
import vct.col.ast.NameExpression;
import vct.col.ast.OperatorExpression;
import vct.col.ast.PrimitiveType;
import vct.col.ast.PrimitiveType.Sort;
import static hre.System.Abort;

public class RewriteDoubleDefinition extends AbstractRewriter {

  public void visit(PrimitiveType t){
    if (t.sort==Sort.Double){
      result=create.class_type(DefineDouble.double_name);
    } else {
      super.visit(t);
    }
  }
  public void visit(OperatorExpression e){
    NameExpression name;
    if (e.getType()==null) Abort("missing type for code from %s",e.getOrigin());
    if (!e.getType().isDouble()){
      super.visit(e);
      return;     
    }
    switch(e.getOperator()){
    case Plus:
      name=create.method_name("plus");
      break;
    default:
      super.visit(e);
      return;
    }
    int N=e.getOperator().arity();
    ASTNode args[]=new ASTNode[N];
    for(int i=0;i<N;i++) args[i]=e.getArg(i).apply(this);
    result=create.invokation(null, false, create.method_name("plus"),args);
  }
}
