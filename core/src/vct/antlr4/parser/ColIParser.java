package vct.antlr4.parser;

import static hre.System.*;
import hre.ast.FileOrigin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import pv.parser.PVFullLexer;
import pv.parser.PVFullParser;
import vct.parsers.*;
import vct.util.Configuration;
import vct.col.ast.ASTClass;
import vct.col.ast.ASTClass.ClassKind;
import vct.col.ast.ASTNode;
import vct.col.ast.ProgramUnit;
import vct.col.rewrite.AbstractRewriter;
import vct.col.rewrite.AnnotationInterpreter;
import vct.col.rewrite.ConvertTypeExpressions;
import vct.col.rewrite.EncodeAsClass;
import vct.col.rewrite.FilterSpecIgnore;
import vct.col.rewrite.FlattenVariableDeclarations;
import vct.col.rewrite.Standardize;
import vct.col.rewrite.StripUnusedExtern;
import vct.col.rewrite.VerCorsDesugar;
import vct.col.syntax.JavaDialect;
import vct.col.syntax.JavaSyntax;
import vct.col.util.SimpleTypeCheck;

/**
 * Parse specified code and convert the contents to COL. 
 */
public class ColIParser implements vct.col.util.Parser {

  protected ProgramUnit parse(String file_name,InputStream stream) throws IOException{
    TimeKeeper tk=new TimeKeeper();

    ANTLRInputStream input = new ANTLRInputStream(stream);
    CLexer lexer = new CLexer(input);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    ErrorCounter ec=new ErrorCounter(file_name);
    CParser parser = new CParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(ec);
    ParseTree tree = parser.compilationUnit();
    Progress("first parsing pass took %dms",tk.show());
    ec.report();
    Debug("parser got: %s",tree.toStringTree(parser));

    ProgramUnit pu=CtoCOL.convert(tree,file_name,tokens,parser);
    Progress("AST conversion took %dms",tk.show());
    Debug("after conversion %s",pu);
    
    pu=new CommentRewriter(pu,new CMLCommentParser(ec)).rewriteAll();
    Progress("Specification parsing took %dms",tk.show());
    ec.report();
    Debug("after comment processing %s",pu);

    pu=new FlattenVariableDeclarations(pu).rewriteAll();
    Progress("Flattening variables took %dms",tk.show());
    Debug("after flattening variable decls %s",pu);
    
    pu=new ConvertTypeExpressions(pu).rewriteAll();
    Progress("converting type expressions took %dms",tk.show());
    Debug("after converting type expression %s",pu);
        
    pu=new SpecificationCollector(pu).rewriteAll();
    Progress("Shuffling specifications took %dms",tk.show());    
    Debug("after collecting specifications %s",pu);

    pu=new VerCorsDesugar(pu).rewriteAll();
    Progress("Desugaring took %dms",tk.show());
    Debug("after desugaring",pu);

    // TODO: encoding as class should not be necessary. 
    pu=new EncodeAsClass(pu).rewriteAll();
    Progress("Encoding as class took %dms",tk.show());
    Debug("after encoding as class %s",pu);
    
    pu=new FilterSpecIgnore(pu).rewriteAll();
    pu=new StripUnusedExtern(pu).rewriteAll();
    Progress("Stripping unused parts took %dms",tk.show());    
    Debug("after stripping unused parts %s",pu);
    
    return pu;
  }
  
  @Override
  public ProgramUnit parse(File file) {
    String file_name=file.toString();
    try {
      InputStream stream =new FileInputStream(file);
      return parse(file_name,stream);
    } catch (FileNotFoundException e) {
      Fail("File %s has not been found",file_name);
    } catch (Exception e) {
      e.printStackTrace();
      Abort("Exception %s while parsing %s",e.getClass(),file_name);
    }
    return null;
  }

}

