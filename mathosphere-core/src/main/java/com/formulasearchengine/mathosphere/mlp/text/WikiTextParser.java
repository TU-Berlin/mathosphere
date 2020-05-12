package com.formulasearchengine.mathosphere.mlp.text;
/**
 * Copyright 2011 The Open Source Research Group, University of Erlangen-Nürnberg <p> Licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at <p> http://www.apache.org/licenses/LICENSE-2.0
 * <p> Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.formulasearchengine.mathosphere.mlp.cli.BaseConfig;
import com.formulasearchengine.mathosphere.mlp.pojos.*;
import com.formulasearchengine.mathosphere.utils.sweble.MlpConfigEnWpImpl;
import com.google.common.collect.Multiset;
import de.fau.cs.osr.ptk.common.AstVisitor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sweble.wikitext.engine.EngineException;
import org.sweble.wikitext.engine.PageId;
import org.sweble.wikitext.engine.PageTitle;
import org.sweble.wikitext.engine.WtEngineImpl;
import org.sweble.wikitext.engine.config.WikiConfig;
import org.sweble.wikitext.engine.nodes.EngPage;
import org.sweble.wikitext.engine.nodes.EngProcessedPage;
import org.sweble.wikitext.parser.nodes.*;
import org.sweble.wikitext.parser.parser.LinkTargetException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A visitor to convert an article AST into a pure text representation. To better understand the
 * visitor pattern as implemented by the Visitor class, please take a look at the following
 * resources: <ul> <li><a href="http://en.wikipedia.org/wiki/Visitor_pattern">http://en.wikipedia
 * .org/wiki/Visitor_pattern</a> (classic pattern)</li> <li><a href="http://www.javaworld.com/javaworld/javatips/jw-javatip98.html">http
 * ://www.javaworld.com/javaworld/javatips/jw-javatip98.html</a> (the version we use here)</li>
 * </ul>
 * <p>
 * The methods needed to descend into an AST and visit the children of a given node <code>n</code>
 * are <ul> <li><code>dispatch(n)</code> - visit node <code>n</code>,</li>
 * <li><code>iterate(n)</code> - visit the <b>children</b> of node <code>n</code>,</li>
 * <li><code>map(n)</code> - visit the <b>children</b> of node <code>n</code> and gather the return
 * values of the <code>visit()</code> calls in a list,</li> <li><code>mapInPlace(n)</code> - visit
 * the <b>children</b> of node <code>n</code> and replace each child node <code>c</code> with the
 * return value of the call to <code>visit(c)</code>.</li> </ul>
 * </p>
 * <p>
 *     The source code of sweble wikitext extension can be found here:<br>
 *     <a href="https://github.com/sweble/sweble-wikitext">https://github.com/sweble/sweble-wikitext</a><br><br>
 *     A complete list of AST wikitext nodes can be found here: <br>
 *     <a href="https://github.com/sweble/sweble-wikitext/blob/develop/sweble-wikitext-components-parent/swc-engine/doc/ast-nodes.txt">
 *         sweble-wikitext/sweble-wikitext-components-parent/swc-engine/doc/ast-nodes.txt</a>
 * </p>
 */
//@SuppressWarnings("unused")
@SuppressWarnings("all")
public class WikiTextParser extends AstVisitor<WtNode> {
    private static final Logger LOG = LogManager.getLogger(WikiTextParser.class.getName());
    private final static Pattern subMatch = Pattern.compile("[{<]sub[}>](.+?)[{<]/sub[}>]");

    public final static Pattern MATH_IN_TEXT_SEQUENCE_PATTERN = Pattern.compile(
            "(?<=^|[^A-Za-z]|)[\\p{IsGreek}\\p{N}\\p{P}\\p{Sm} ]+(?=[^A-Za-z]|$)"
    );

    private final static Pattern MATH_END_PATTERN = Pattern.compile("^\\s*(.*)\\s*([.,;!?]+)\\s*$");

    private final static Pattern MML_TOKENS_PATTERN = Pattern.compile(
            "<(?:m[a-z]+|apply|c[in]|csymbol|semantics)>"
    );

    private final static WikiConfig config = MlpConfigEnWpImpl.generate();
    private final static WtEngineImpl engine = new WtEngineImpl(config);
    private static final Pattern ws = Pattern.compile("\\s+");
    private static int i = 0;

    private final EngProcessedPage page;

//    private List<MathTag> mathTags = new ArrayList<>();
//    private List<WikidataLink> links = new ArrayList<>();
//    private Map<Integer, WikiCitation> citations = new HashMap<>();

    private DocumentMetaLib lib;

    private StringBuilder sb;
    private StringBuilder line;
    private int extLinkNum;
    private WikidataLinkMap wl = null;

    /**
     * Becomes true if we are no long at the Beginning Of the whole Document.
     */
    private boolean pastBod;
    private int needNewlines;
    private boolean needSpace;
    private boolean noWrap;
    private LinkedList<String> sections;
    private PageTitle pageTitle;
    private String texInfoUrl;

    private MathTag previousMathTag = null;
    private String previousMathTagEndingSplit = null;

    private boolean suppressOutput = false;

    private boolean skipHiddenMath;

    public WikiTextParser(String partialWikiDoc) throws LinkTargetException, EngineException {
        this(new RawWikiDocument("unknown-title", -1, partialWikiDoc));
    }

    public WikiTextParser(RawWikiDocument wikidoc) throws LinkTargetException, EngineException {
        pageTitle = PageTitle.make(config, wikidoc.getTitle());
        PageId pageId = new PageId(pageTitle, -1);
        page = engine.postprocess(pageId, wikidoc.getContent(), null);
        texInfoUrl = (new BaseConfig()).getTexvcinfoUrl();
        lib = new DocumentMetaLib();
    }

    public WikiTextParser(RawWikiDocument wikidoc, BaseConfig config) throws LinkTargetException, EngineException {
        this(wikidoc);
        if (config.getWikiDataFile() != null) {
            wl = new WikidataLinkMap(config.getWikiDataFile());
        } else {
            wl = null;
        }
        texInfoUrl = config.getTexvcinfoUrl();
    }

    public List<String> parse() {
        try {
            return (List<String>) this.go(page.getPage());
        } catch (Exception e) {
            LOG.error("Error parsing page " + this.pageTitle, e);
            List<String> txt = new LinkedList<>();
            txt.add("");
            return txt;
        }
    }

    public DocumentMetaLib getMetaLibrary() {
        return lib;
    }

    public boolean isSkipHiddenMath() {
        return skipHiddenMath;
    }

    public void setSkipHiddenMath(boolean skipHiddenMath) {
        this.skipHiddenMath = skipHiddenMath;
    }

    @Override
    protected Object after(WtNode node, Object result) {
        finishLine();

        // This method is called by go() after visitation has finished
        // The return value will be passed to go() which passes it to the caller

        // flush the last section
        sections.addLast(sb.toString());
        return sections;
    }

    // =========================================================================

    @Override
    protected WtNode before(WtNode node) {
        // This method is called by go() before visitation starts
        sb = new StringBuilder();
        line = new StringBuilder();
        extLinkNum = 1;
        pastBod = false;
        needNewlines = 0;
        needSpace = false;
        noWrap = false;
        sections = new LinkedList<>();
        return super.before(node);
    }

    private boolean detectHiddenMath(WtNode i) {
        if (skipHiddenMath){
            return false;
        }
        if (i.size() == 1 && i.get(0) instanceof WtText) {
            final String tex = getTex(i, false);
            if (tex != null) {
                addMathTag(tex, WikiTextUtils.MathMarkUpType.MATH_TEMPLATE);
                return true;
            }
        } else {
            if (i.size() == 2 && i.get(0) instanceof WtText && i.get(1) instanceof WtXmlElement) {
                //discover hidden subscripts
                final WtXmlElement xml = (WtXmlElement) i.get(1);
                if (xml.getName().matches("sub") &&
                        xml.getBody().size() == 1 &&
                        xml.getBody().get(0) instanceof WtText) {
                    //String subtext = ((WtText) ((WtXmlElement) i.get(1)).getBody().get(0)).getContent();
                    final String subTex = getTex(xml.getBody(), true);
                    final String mainTex = getTex(i, true);
                    if (mainTex != null) {
                        String tex = mainTex + "_{" + subTex + "}";
                        addMathTag(tex, WikiTextUtils.MathMarkUpType.MATH_TEMPLATE);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // =========================================================================

    private void finishLine() {
        if ( previousMathTagEndingSplit != null ) {
            line.append(previousMathTagEndingSplit);
            previousMathTagEndingSplit = null;
        }
        sb.append(line.toString());
        sb.append(" ");
        line.setLength(0);
    }

    private String getTex(WtNode i, boolean force) {
        if (i.get(0) instanceof WtText) {
            String content = ((WtText) i.get(0)).getContent().trim();
            String tex = replaceClearMath(content);
            if (tex.length() == 1 || !content.equals(tex)) {
                // it triggered a math replacement... so first we have to fix unicode, if exists
                String unicodeTex = replaceMathUnicode(tex);
                Multiset<String> idents;
                try {
                    idents = TexInfo.getIdentifiers(unicodeTex, texInfoUrl);
                } catch (XPathExpressionException | ParserConfigurationException | IOException
                        | SAXException | TransformerException ignored) {
                    return null;
                }
                // a single UTF character should always be interpreted as math, no?
                if ( tex.length() == 1 && !tex.equals(unicodeTex) ){
                    return unicodeTex;
                }
                if (idents.size() == 0 && !force) {
                    return null;
                }
                if (i instanceof WtBold) {
                    unicodeTex = "\\mathbf{" + unicodeTex + "}";
                }
                return unicodeTex;
            }
            if (force) {
                return tex;
            }
        }
        return null;
    }

    private void handleLatexMathTag(WtNode n, String content) {
        //content = content.replaceAll("'''([a-zA-Z]+)'''","\\mathbf{$1}");
        content = replaceMathUnicode(replaceClearMath(content));
        addMathTag(content, WikiTextUtils.MathMarkUpType.MATH_TEMPLATE);
    }

    private void newline(int num) {
        if (pastBod) {
            if (num > needNewlines) {
                needNewlines = num;
            }
        }
    }

    public void visit(WtNode n) {
        // Fallback for all nodes that are not explicitly handled below
//		System.out.println(n.getNodeName());
//		write("<");
//		write(n.getNodeName());
//		write(" />");
    }

    public void visit(WtNodeList n) {
        iterate(n);
    }

    public void visit(WtUnorderedList e) {
        iterate(e);
    }

    public void visit(WtOrderedList e) {
        iterate(e);
    }

    public void visit(WtListItem item) {
        writeNewlines(1);
//        finishLine();
        iterate(item);
    }

    public void visit(EngPage p) {
        iterate(p);
    }

    public void visit(WtText text) {
        Matcher m = MATH_IN_TEXT_SEQUENCE_PATTERN.matcher(text.getContent());

        while (m.find()) {
            int startIdx = m.start();
            if ( startIdx > 0 && previousMathTag != null ) {
                // well, there were actually text, before we hit first... so
                // time to reset previous hit...
                resetPreviousMath();
            }

            String potentialMath = m.group(0);
            String replaced = replaceClearMath(potentialMath);
            replaced = replaceMathUnicode(replaced);

            if ( !replaced.equals(potentialMath) ) {
                // so there were replacements, we can assume that's math...
                // delete the hit
                m.appendReplacement(line, "");
                // add math tag at same position.
                if ( previousMathTag != null ) {
                    if ( previousMathTagEndingSplit != null ) {
                        replaced = previousMathTagEndingSplit + replaced;
                        previousMathTagEndingSplit = null;
                    }

                    Matcher mm = MATH_END_PATTERN.matcher(replaced);
                    if ( mm.matches() ){
                        replaced = mm.group(1);
                        previousMathTagEndingSplit = mm.group(2);
                    } else previousMathTagEndingSplit = null;

                    previousMathTag.extendContent(replaced);
                } else {
                    addMathTag(replaced, WikiTextUtils.MathMarkUpType.LATEX);
                }
            } else {
                // ok, that seems to be no math... so let's continue
                m.appendReplacement(line, potentialMath);
                previousMathTag = null;
            }
        }

        // done, lets add the rest
        m.appendTail(line);
//        write(text.getContent());
    }

    public void visit(WtWhitespace w) {
        write(" ");
    }

    public void visit(WtBold b) {
        if ( checkItalicBoldForMath(b) ) {
            return;
        }
        write("\"");
        iterate(b);
        write("\"");
    }

    public void visit(WtItalics i) {
        if ( checkItalicBoldForMath(i) ) {
            return;
        }
        write("\"");
        iterate(i);
        write("\"");
    }

    private boolean checkItalicBoldForMath(WtNode n) {
        if ( n.get(0) instanceof WtInternalLink ) {
            WtInternalLink in = (WtInternalLink)n.get(0);
            WtNode title = in.getTitle();
            if ( title == null || title.size() == 0) title = in.getTarget();
            if ( title != null && title.size() != 0) {
                return detectHiddenMath(title);
            }
        }
        return detectHiddenMath(n);
    }

    public void visit(WtXmlCharRef cr) {
        write(Character.toChars(cr.getCodePoint()));
    }

    public void visit(WtXmlEntityRef er) {
        String ch = er.getResolved();
        if (ch == null) {
            write('&');
            write(er.getName());
            write(';');
        } else {
            write(ch);
        }
    }

    // =========================================================================
    // Stuff we want to hide

    public void visit(WtUrl wtUrl) {
        if (!wtUrl.getProtocol().isEmpty()) {
            write(wtUrl.getProtocol());
            write(':');
        }
        write(wtUrl.getPath());
    }

    public void visit(WtExternalLink link) {
        write('[');
        write(extLinkNum++);
        write(']');
    }

    public void visit(WtInternalLink link) {
        String linkName = link.getTarget().getAsString().split("#")[0];
        if (wl != null) {
            String newName = wl.title2Data(linkName);
            if (newName != null) {
                write("LINK_" + newName);
                return;
            }
        }
        WikidataLink wl = new WikidataLink(linkName);
        write("LINK_" + wl.getContentHash());
        needSpace = true;
        if (link.getTitle().size() > 0) {
            StringBuilder tmp = this.line;
            this.line = new StringBuilder();
            iterate(link.getTitle());
            wl.setTitle(this.line.toString());
            this.line = tmp;
        }
        lib.addLink(wl);
    }

    public void visit(WtSection s) {
        finishLine();
//        StringBuilder saveSb = sb;
//        boolean saveNoWrap = noWrap;
//
//        // TODO shall we ignore title of section
//        sb = new StringBuilder();
//        noWrap = true;
//
//        iterate(s.getHeading());
//        finishLine();
//        String title = sb.toString().trim();
//
//        sb = saveSb;

//        if (s.getLevel() >= 1) {
//            while (sections.size() > s.getLevel()) {
//                sections.removeLast();
//            }
//            while (sections.size() < s.getLevel()) {
//                sections.add(1);
//            }
//
//            StringBuilder sb2 = new StringBuilder();
//            for (int i = 0; i < sections.size(); ++i) {
//                if (i < 1) {
//                    continue;
//                }
//
//                sb2.append(sections.get(i));
//                sb2.append('.');
//            }
//
//            if (sb2.length() > 0) {
//                sb2.append(' ');
//            }
//            sb2.append(title);
//            title = sb2.toString();
//        }
//
//        newline(2);
//        write(title);
//        newline(1);
//        write(strrep('-', title.length()));
//        newline(2);

        // if there is stuff already in the string builder, flush it to sections
        if ( sb.length() > 1 ) {
            sections.addLast(sb.toString());
            sb = new StringBuilder();
        }

//        noWrap = saveNoWrap;
        try {
            // Don't care about errors
            iterate(s.getBody());
        } catch (Exception e) {
            LOG.info("Problem processing page ", pageTitle.getTitle(), e);
            e.printStackTrace();
        }

//        while (sections.size() > s.getLevel()) {
//            sections.removeLast();
//        }
//        sections.add(sections.removeLast() + 1);
    }

    public void visit(WtParagraph p) {
        iterate(p);
        newline(2);
    }

    public void visit(WtHorizontalRule hr) {
        newline(1);
//        write(strrep('-', 10));
//        newline(2);
    }

    public void visit(WtXmlElement e) {
        String name = e.getName();
        boolean sup = true;
        switch ( name.toLowerCase() ) {
            case "br":
                newline(1);
                break;
            case "var":
                WtNode wtNodes = e.getBody().get(0);
                String content;
                if (wtNodes instanceof WtText) {
                    content = ((WtText) wtNodes).getContent().trim();
                    handleLatexMathTag(e, content);
                } else if (wtNodes instanceof WtInternalLink) {
                    //TODO: do not throw away the information of the link from WtInternalLink.getTarget()
                    //Identifier is more important than link. Link maybe helpful for wikidata.
                    content = ((WtText) ((WtInternalLink) e.getBody().get(0)).getTitle().get(0)).getContent().trim();
                    handleLatexMathTag(e, content);
                }
                break;
            case "text":
                iterate(e.getBody());
                break;
            case "sub":
                sup = false;
            case "sup":
                WtNode bodyNode = e.getBody();
                String contentStr = handleSuccessiveSubSups(bodyNode);
                String c = sup ? "^{" : "_{";
                c += contentStr + "}";
                if ( previousMathTag != null ) {
                    try {
                        if ( previousMathTagEndingSplit != null ){
                            c = previousMathTagEndingSplit + c;
                            previousMathTagEndingSplit = null;
                        }
                        previousMathTag.extendContent(c);
                    } catch ( IllegalArgumentException ie ) {
                        LOG.warn("Unable to extend previous math expression");
                    }
                } else {
                    LOG.warn("Found sub/sup but not after a math expression. We just put it as math to the text.");
                    addMathTag(c, WikiTextUtils.MathMarkUpType.LATEX);
                }
        }
    }

    private String handleSuccessiveSubSups(WtNode element) {
        StringBuilder content = new StringBuilder();
        for (WtNode e : element) {
            if (e.size() > 1) {
                content.append(handleSuccessiveSubSups(e));
                continue;
            }

            if (e instanceof WtItalics) {
                e = e.get(0); // text node inside italics
            }

            if (e instanceof WtText) {
                String c = ((WtText) e).getContent();
                content.append(c);
            } else {
                LOG.warn("Unable to parse " + e + " inside Sub/Sup tag. Ignore it.");
            }
        }
        return content.toString();
    }

    public void visit(WtImageLink n) {
        iterate(n.getTitle());
    }

    public void visit(WtIllegalCodePoint n) {
    }

    public void visit(WtXmlComment n) {
    }

    public void visit(WtTable b) {
        iterate(b.getBody());
    }

    public void visit(WtTableRow b) {
        iterate(b);
    }

    public void visit(WtTableCell b) {
        iterate(b);
    }

    public void visit(WtTableImplicitTableBody b) {
        iterate(b);
    }

    public void visit(WtTableHeader b) {
        iterate(b);
    }

    public void visit(WtTableCaption b) {
        iterate(b);
    }

    public void visit(WtNewline n) {
        // ignore new lines
        writeNewlines(1);
    }

    // =========================================================================

    public void visit(WtTemplate n) {
        try {
            WtTemplateArgument arg0;
            String content;
            WikiCitation cite;
            String name = n.getName().getAsString();

            if ( name.toLowerCase().startsWith("equation") ) {
                handleEquationTemplate(n);
                return;
            }

            switch (name.toLowerCase()) {
                case "math":
                    arg0 = (WtTemplateArgument) n.getArgs().get(0);
                    content = "";
                    for ( int i = 0; i < arg0.getValue().size(); i++ ){
                        WtNode node = arg0.getValue().get(i);
                        if ( node instanceof WtText ) {
                            content += ((WtText) node).getContent().trim();
                        } else if ( node instanceof WtTemplate ) {
                            content += innerMathTemplateReplacement((WtTemplate)node);
                        } else {
                            LOG.warn("Ignore unknown node within math template: " + node.toString());
                        }
                    }

                    handleLatexMathTag(n, content);
                    break;
                case "mvar":
                    arg0 = (WtTemplateArgument) n.getArgs().get(0);
                    content = ((WtText) arg0.getValue().get(0)).getContent().trim();
                    content = replaceMathUnicode(replaceClearMath(content));
                    addMathTag(content, WikiTextUtils.MathMarkUpType.MVAR_TEMPLATE);
                    break;
                case "numblk":
                    // https://en.wikipedia.org/wiki/Template:NumBlk
                    // the second argument is always math, so iterate just over the math
                    arg0 = (WtTemplateArgument) n.getArgs().get(1);
                    iterate(arg0.getValue());
                    break;
                case "Citation":
                    cite = new WikiCitation(n.toString());
                    lib.addCite(cite);
                    break;
                case "dlmf":
                    cite = new WikiCitation("dlmf", n.toString());
                    lib.addCite(cite);
                    break;
                case "short description":
                case "for":
                case "use american english":
                    LOG.warn("Ignore template: " + name);
                    break;
                case "pi":
                    // I can't believe we are doing this... who thought its a good idea to create a freaking template for PI!!!
                    addMathTag("\\pi", WikiTextUtils.MathMarkUpType.LATEX);
                default:
                    iterate(n.getArgs());
            }
        } catch (Exception e) {
            LOG.info("Problem prcessing page", pageTitle.getTitle(), e);
        }
    }

    private void handleEquationTemplate(WtTemplate equation) {
        WtTemplateArguments args = equation.getArgs();
        WtTemplateArgument titleArg = null, equationArg = null;
        for (WtNode wtNode : args) {
            WtTemplateArgument arg = (WtTemplateArgument) wtNode;
            if ( arg.getName().size() < 1 ) continue;
            String name = arg.getName().getAsString();
            if ( "title".equals(name) ) {
                titleArg = arg;
            } else if ( "equation".equals(name) ) {
                equationArg = arg;
            }
        }

        if ( titleArg != null ) {
            dispatch(titleArg.getValue());
            write(": ");
        }

        if ( equationArg != null ) {
            dispatch(equationArg.getValue());
        }
    }

    public String innerMathTemplateReplacement(WtTemplate t) {
        String name = t.getName().getAsString();
        WtTemplateArgument arg;
        String result = "";
        switch (name.toLowerCase()) {
            case "pi":
                result = " \\pi ";
                break;
            case "=":
                result = " = ";
                break;
            case "su":
                WtTemplateArguments args = t.getArgs();
                String sub = "", sup = "";
                for ( int i = 0; i < args.size(); i++ ) {
                    arg = (WtTemplateArgument)args.get(i);
                    String key = arg.getName().getAsString();
                    Boolean subKey = null;
                    if ( key.equals("b") ) {
                        subKey = true;
                    } else if ( key.equals("p") ) {
                        subKey = false;
                    }

                    if ( subKey != null ) {
                        String c = ((WtText)arg.getValue().get(0)).getContent();
                        if ( subKey ) sub = "_{"+c+"}";
                        else sup = "^{"+c+"}";
                    }
                }
                result = sub+sup;
                break;
            case "sub":
                arg = (WtTemplateArgument) t.getArgs().get(0);
                result = ((WtText)arg.getValue().get(0)).getContent();
                result = "_{"+result+"}";
                break;
            case "sup":
                arg = (WtTemplateArgument) t.getArgs().get(0);
                result = ((WtText)arg.getValue().get(0)).getContent();
                result = "^{"+result+"}";
                break;
            default: return null;
        }
        return result;
    }

    public void visit(WtTemplateArgument n) {
        if (!detectHiddenMath(n.getValue())) {
            iterate(n.getValue());
        }
    }

    public void visit(WtTemplateParameter n) {
    }

    public void visit(WtTagExtension n) {
        boolean chem = false;
        switch (n.getName()) {
            case "ce":
            case "chem":
                chem = true;
            case "math":
                WikiTextUtils.MathMarkUpType markUpType;
                String content = "";
                if (chem) {
                    markUpType = WikiTextUtils.MathMarkUpType.LATEXCE;
                } else if ( hasMathMLNamespaceAttribute(n.getXmlAttributes()) || hasMathMLTokens(n.getBody()) ) {
                    LOG.debug("Identified MathML");
                    markUpType = WikiTextUtils.MathMarkUpType.MATHML;
                } else {
                    markUpType= WikiTextUtils.MathMarkUpType.LATEX;
                }
                addMathTag(n.getBody().getContent(), markUpType);
                break;
            case "ref":
                String attribute = "";
                if ( n.getXmlAttributes().size() > 0 ) {
                    for (WtNode wtNode : n.getXmlAttributes()) {
                        WtXmlAttribute att = (WtXmlAttribute) wtNode;
                        if (att.getName().getAsString().equals("name")) {
                            attribute = ((WtText) att.getValue().get(0)).getContent();
                            break;
                        }
                    }
                }

                WikiCitation cite = new WikiCitation(
                        attribute,
                        n.getBody().toString()
                );

                lib.addCite(cite);
                write(" "+cite.placeholder());
        }
    }

    private boolean hasMathMLNamespaceAttribute(WtXmlAttributes attributes) {
        for (WtNode attribute : attributes) {
            WtXmlAttribute att = (WtXmlAttribute) attribute;
            String name = att.getName().getAsString();
            if ("xmlns".equals(name.toLowerCase())) {
                // ok its xmlns, lets check if its actually mathml
                String value = ((WtText) att.getValue().get(0)).getContent();
                if ("http://www.w3.org/1998/Math/MathML".equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasMathMLTokens(WtTagExtensionBody body) {
        String mightBeMathML = body.getContent();
        Matcher mmlMatcher = MML_TOKENS_PATTERN.matcher(mightBeMathML);
        return mmlMatcher.find();
    }

    private void addMathTag(String content, WikiTextUtils.MathMarkUpType type) {
        if ( previousMathTag != null ) {
            try {
                previousMathTag.extendContent(previousMathTagEndingSplit+" ");

                Matcher m = MATH_END_PATTERN.matcher(content);
                if ( m.matches() ){
                    content = m.group(1);
                    previousMathTagEndingSplit = m.group(2);
                } else previousMathTagEndingSplit = null;

                previousMathTag.extendContent(content);
                return;
            } catch (IllegalArgumentException iae) {
                LOG.warn("Unable to extend previous mathematical expression. Continue as usually.");
            }
        }

        Matcher m = MATH_END_PATTERN.matcher(content);
        if ( m.matches() ){
            content = m.group(1);
            previousMathTagEndingSplit = m.group(2);
        } else previousMathTagEndingSplit = null;

        MathTag tag = new MathTag(content, type);
        previousMathTag = tag;

        lib.addFormula(tag);
        if (needNewlines > 0) {
            write(" ");
        }

        needSpace = true;
        writeWord(tag.placeholder());
        needSpace = true;
    }

    public void visit(WtPageSwitch n) {
    }

    private void wantSpace() {
        if (pastBod) {
            needSpace = true;
        }
    }

    public static String replaceClearMath(String content) {
        return subMatch.matcher(content)
                .replaceAll("_{$1}")
                .replaceAll("[{<]sup[}>](.+?)[{<]/sup[}>]", "^{$1}")
                .replaceAll("'''\\[{0,2}(\\S)]{0,2}'''", "\\\\mathbf{$1}")
                .replaceAll("''\\[{0,2}(\\S)]{0,2}''", "$1");
    }

    public static String replaceMathUnicode(String content) {
        return UnicodeMap.string2TeX(content);
    }

    private void write(String s) {
        if ( !s.matches("\\s*FORMULA.*") ) {
            resetPreviousMath();
        }

        if (suppressOutput){
            return;
        }
        if (s.isEmpty()) {
            return;
        }

        if (Character.isSpaceChar(s.charAt(0))) {
            wantSpace();
        }

        String[] words = ws.split(s);
        for (int i = 0; i < words.length; ) {
            writeWord(words[i]);
            if (++i < words.length) {
                wantSpace();
            }
        }

        final char lastChar = s.charAt(s.length() - 1);
        if (Character.isSpaceChar(lastChar) || lastChar == '\n') {
            wantSpace();
        }
    }

    private void write(char[] cs) {
        write(String.valueOf(cs));
    }

    private void write(char ch) {
        writeWord(String.valueOf(ch));
    }

    private void write(int num) {
        writeWord(String.valueOf(num));
    }

    /**
     * New lines are problematic for the PoS-Tagger. Hence, it is better to not add line breaks, since we
     * have a system implemented that handles sections well.
     * @param num .
     * @deprecated just do not use new lines anymore.
     */
    @Deprecated
    private void writeNewlines(int num) {
        finishLine();
//        sb.append(strrep('\n', num));
        needNewlines = 0;
        needSpace = false;
    }

    private void resetPreviousMath() {
        previousMathTag = null;
        if ( previousMathTagEndingSplit != null ) {
            line.append(previousMathTagEndingSplit);
            previousMathTagEndingSplit = null;
        }
    }

    private void writeWord(String s) {
        if ( !s.matches("\\s*FORMULA.*") ) {
            resetPreviousMath();
        }

        int length = s.length();
        if (length == 0) {
            return;
        }

        if (needSpace && needNewlines <= 0) {
            line.append(' ');
        }

        if (needNewlines > 0) {
            writeNewlines(needNewlines);
        }

        needSpace = false;
        pastBod = true;
        line.append(s);
    }

    /**
     * Process only the tags without to generate text output.
     * Suppressing the output might be useful if one is only interested in the math tags and not in the text.
     * In that case this option speeds up the process
     *
     */
    public void processTags() {
        this.suppressOutput = true;
        this.parse();
        this.suppressOutput = false;
    }
}

