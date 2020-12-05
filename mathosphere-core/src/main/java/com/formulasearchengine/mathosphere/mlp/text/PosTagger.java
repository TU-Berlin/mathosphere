package com.formulasearchengine.mathosphere.mlp.text;

import com.alexeygrigorev.rseq.*;
import com.formulasearchengine.mathosphere.mlp.cli.BaseConfig;
import com.formulasearchengine.mathosphere.mlp.pojos.*;
import com.formulasearchengine.mathosphere.mlp.rus.RusPosAnnotator;
import com.google.common.collect.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.MorphaAnnotator;
import edu.stanford.nlp.pipeline.POSTaggerAnnotator;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.util.CoreMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class PosTagger {
  private static BaseConfig config;

  private static final Logger LOGGER = LogManager.getLogger(PosTagger.class.getName());

  private static final Set<String> SYMBOLS = ImmutableSet.of("<", "=", ">", "≥", "≤", "|", "/", "\\", "[",
    "]", "*");
  private static final Map<String, String> BRACKET_CODES = ImmutableMap.<String, String>builder()
    .put("-LRB-", "(").put("-RRB-", ")").put("-LCB-", "{").put("-RCB-", "}").put("-LSB-", "[")
    .put("-RSB-", "]").build();

  public static PosTagger create(BaseConfig cfg) {
    config = cfg;
    Properties props = new Properties();
    props.put("annotators", "tokenize, ssplit");
    props.put("tokenize.options", "untokenizable=firstKeep,strictTreebank3=true,ptb3Escaping=true,escapeForwardSlashAsterisk=false");
    props.put("ssplit.newlineIsSentenceBreak", "two");
    props.put("maxLength", 50);
    StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

    if ("en".equals(cfg.getLanguage())) {
      POSTaggerAnnotator modelBasedPosAnnotator = new POSTaggerAnnotator(config.getModel(), false);
      pipeline.addAnnotator(modelBasedPosAnnotator);
      pipeline.addAnnotator(new MorphaAnnotator(false)); // for lemmas
    } else if ("ru".equals(cfg.getLanguage())) {
      pipeline.addAnnotator(new RusPosAnnotator());
      pipeline.addAnnotator(new MorphaAnnotator(false)); // for lemmas
    } else {
      throw new IllegalArgumentException("Cannot deal with language " + config.getLanguage());
    }

    return new PosTagger(pipeline);
  }

  private final StanfordCoreNLP nlpPipeline;

  public PosTagger(StanfordCoreNLP nlpPipeline) {
    this.nlpPipeline = nlpPipeline;
  }

  /**
   * Performs POS-Tagging via CoreNLP lib and returns a list of sentences (a sentence is a list of words).
   * @param sections the cleaned wikitext (formulae replaced by FORMULA tags etc)
   * @param lib the meta information library will be updated with new positions according to their appearance after
   *            tokenize by the StanfordNLP tagger.
   * @return POS-tagged list of sentences.
   */
  public List<List<List<Word>>> annotate(List<String> sections, DocumentMetaLib lib) {
    List<List<List<Word>>> result = Lists.newArrayList();
    for ( int sec = 0; sec < sections.size(); sec++ ) {
      String cleanText = sections.get(sec);

      HashMap<String, WikidataLink> tempLinkMap = new HashMap<>();
      cleanText = undoSingleTermTextLinkage(cleanText, tempLinkMap, lib);

      Annotation document = new Annotation(cleanText);
      nlpPipeline.annotate(document);
      List<List<Word>> sentences = Lists.newArrayList();

      for (CoreMap sentence : document.get(SentencesAnnotation.class)) {
        List<Word> words = Lists.newArrayList();

        final List<CoreLabel> coreLabels = sentence.get(TokensAnnotation.class);
        for ( int i = 0; i < coreLabels.size(); i++ ) {
          CoreLabel token = coreLabels.get(i);
          String textToken = token.word();
          String pos = token.tag();
          String lemma = token.lemma();
          SpecialToken specialToken = null;
          Position p = new Position(result.size(), sentences.size(), words.size());
          p.setDocumentLib(lib);
          // underscores are split in v3.9.2, see: https://github.com/stanfordnlp/CoreNLP/issues/942
          if (textToken.startsWith(PlaceholderLib.FORMULA)) {
            String underscore = handleUnderscore(coreLabels, i);
            if ( underscore != null ){
              i = i+2; // skip underscore tokens
              textToken += underscore;
            }
            specialToken = lib.getFormulaLib().get(textToken);
            words.add(new Word(p, textToken, lemma, PosTag.MATH));
          } else if (SYMBOLS.contains(textToken)) {
            words.add(new Word(p, textToken, lemma, PosTag.SYMBOL).setOriginalPosTag(pos));
          } else if (BRACKET_CODES.containsKey(textToken)) {
//            words.add(new Word(p, BRACKET_CODES.get(textToken), pos));
            words.add(new Word(p, textToken, lemma, pos));
          } else if (textToken.startsWith(PlaceholderLib.LINK)) {
            String underscore = handleUnderscore(coreLabels, i);
            if ( underscore != null ){
              i = i+2; // skip underscore tokens
              textToken += underscore;
            }
            specialToken = lib.getLinkLib().get(textToken);
            WikidataLink link = (WikidataLink) specialToken;
            String linkText = link.getTitle() == null ? link.getContent() : link.getTitle();
            words.add(new Word(p, link.placeholder(), lemma, PosTag.LINK).setOriginalPosTag(pos));
          } else if (textToken.startsWith(PlaceholderLib.CITE)) {
            String underscore = handleUnderscore(coreLabels, i);
            if ( underscore != null ){
              i = i+2; // skip underscore tokens
              textToken += underscore;
            }
            specialToken = lib.getCiteLib().get(textToken);
            // in the lib, but no longer in the text
//            words.add(new Word(p, textToken, PosTag.CITE));
          } else if ( textToken.toLowerCase().matches("polynomials?") ) {
            words.add(new Word(p, textToken, lemma, PosTag.NOUN));
          } else if (tempLinkMap.containsKey(textToken) ) {
            WikidataLink link = tempLinkMap.get(textToken);
            String txt = link.getTitle() == null ? link.getContent() : link.getTitle();
            words.add(new Word(p, link.placeholder(), lemma, PosTag.LINK).setOriginalPosTag(pos));
          } else {
            words.add(new Word(p, textToken, lemma, pos));
          }
          if ( specialToken != null ) {
            specialToken.addPosition(p);
          }
        }
        sentences.add(words);
      }
      result.add(sentences);
    }

    lib.setDocumentLength(result);
    return result;
  }

  private String undoSingleTermTextLinkage(String text, HashMap<String, WikidataLink> linkMap, DocumentMetaLib lib) {
    Map<String, SpecialToken> linkLib = lib.getLinkLib();
    java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile("LINK_[0-9a-zA-Z]+");
    StringBuilder sb = new StringBuilder();
    java.util.regex.Matcher matcher = linkPattern.matcher(text);
    while(matcher.find()) {
      String id = matcher.group();
      WikidataLink link = (WikidataLink) linkLib.get(id);
      if ( link == null ) {
        matcher.appendReplacement(sb, id); // nothing to replace
        continue;
      }

      String txt = link.getTitle() == null ? link.getContent() : link.getTitle();
      txt = txt.trim();
      // if the link is replaced by multiple words, it can be considered as a single noun. Hence,
      // we keep the word as it is.
      if ( txt.contains(" ") ) {
        // multiple words, simply put back "LINK_id"
        matcher.appendReplacement(sb, id);
      } else {
        // a single word, might be not a noun in the context
        linkMap.put(txt, link);
        txt = java.util.regex.Matcher.quoteReplacement(txt);
        matcher.appendReplacement(sb, txt);
      }
    }

    matcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * Since 3.9.2, underscore expressions are split (e.g., LINK_3 is split into three tokens LINK, _, 3).
   * It was promised to be fixed in an issue: https://github.com/stanfordnlp/CoreNLP/issues/942
   * However, the latest release is still 3.9.2, even though they working on release 4.0.0.
   *
   * Luckily, the admit it was a regression and fixed it for 4.0.0. We are working now on 4.0.0,
   * hence this method is no longer necessary. We just keep it, just in case.
   */
  private String handleUnderscore(List<CoreLabel> coreLabels, int i) {
    if ( i >= coreLabels.size()-2 ) return null;
    CoreLabel next = coreLabels.get(i+1);
    if ( next.get(TextAnnotation.class).equals("_") ) {
      next = coreLabels.get(i+2);
      return "_" + next.get(TextAnnotation.class);
    }
    return null;
  }

  protected static List<Sentence> convertToSentences(
          List<List<List<Word>>> input,
          Map<String, MathTag> formulaIndex,
          List<List<GrammaticalStructure>> strucs
  ) {
    List<Sentence> result = Lists.newArrayListWithCapacity(input.size());

    for ( int i = 0; i < input.size(); i++ ) {
      List<List<Word>> sentences = input.get(i);
      List<GrammaticalStructure> innerStrucs = strucs.get(i);
      for ( int j = 0; j < sentences.size(); j++ ) {
        List<Word> sentence = sentences.get(j);
        GrammaticalStructure gs = innerStrucs.get(j);
        Sentence s = toSentence(i, sentence, formulaIndex, gs);
        result.add(s);
      }
    }

    return result;
  }

  /**
   * Converts a given list of words (considered as a single sentence) to a list of sentences.
   * @param input
   * @param formulaIndex
   * @return
   */
  protected static Sentence toSentence(
          int section,
          List<Word> input,
          Map<String, MathTag> formulaIndex,
          GrammaticalStructure gs
  ) {
    List<Word> words = Lists.newArrayListWithCapacity(input.size());
    Set<String> sentenceIdentifiers = Sets.newHashSet();
    Set<MathTag> formulas = Sets.newHashSet();

    for (int position = 0; position < input.size(); position++) {
      Word w = input.get(position);
      String word = w.getWord();
      String pos = w.getPosTag();

      // there are no identifiers anymore, only MOI/complex math
//      if (allIdentifiers.contains(word) && !PosTag.IDENTIFIER.equals(pos)) {
//        words.add(new Word(word, PosTag.IDENTIFIER));
//        sentenceIdentifiers.add(word);
//        continue;
//      }

      if (PosTag.MATH.equals(pos)) {
        String formulaKey = word;
        if (word.length() > 40) {
          formulaKey = word.substring(0, 40);
        }

        MathTag formula = formulaIndex.get(formulaKey);
        if (formula == null) {
          LOGGER.warn("formula {} does not exist", word);
          words.add(w);
          continue;
        }

        formulas.add(formula);

        Multiset<String> formulaIdentifiers = formula.getIdentifiers(config);
        sentenceIdentifiers.addAll(formulaIdentifiers);
        words.add(w);
        // only one occurrence of one single idendifier
//        if (formulaIdentifiers.size() == 1) {
//          String id = Iterables.get(formulaIdentifiers, 0);
//          LOGGER.debug("Converting formula {} to identifier {}", formula.getKey(), id);
//          words.add(new Word(id, PosTag.IDENTIFIER));
//          sentenceIdentifiers.add(id);
//        } else {
//          words.add(w);
//        }

        if (word.length() > 40) {
          String rest = word.substring(40, word.length());
          words.add(new Word(rest, PosTag.SUFFIX));
        }

        continue;
      }

      words.add(w);
    }

    return new Sentence(section, words, sentenceIdentifiers, formulas, gs);
  }

  /**
   * Concatenate words according to their POS-tags (eg multiple nouns are merged to noun phrases).
   * @param sentences list of sentences
   * @return concatenated version of the input sentences
   */
  public static List<List<List<Word>>> concatenateTags(List<List<List<Word>>> sentences) {
    List<List<List<Word>>> results = Lists.newArrayListWithCapacity(sentences.size());

    for ( List<List<Word>> sec : sentences ) {
      List<List<Word>> innerRes = Lists.newArrayListWithCapacity(sec.size());
      for (List<Word> sentence : sec) {
        List<Word> res = concatenatePhrases(sentence);
        innerRes.add(res);
      }
      results.add(innerRes);
    }

    return results;
  }

  /**
   * See {@link #concatenateLinks(List, Set)}. Use {@link #concatenatePhrases(List)} directly.
   * @param sentence ..
   * @param allIdentifiers ..
   * @return ..
   */
  @Deprecated
  private static List<Word> concatenateSingleWordList(List<Word> sentence, Set<String> allIdentifiers) {
    // links
    List<Word> result;
    if (config.getUseTeXIdentifiers()) {
      result = sentence;
    } else {
      result = concatenateLinks(sentence, allIdentifiers);
    }

    // noun phrases
    return concatenatePhrases(result);
  }

  /**
   * With the new sweble parser {@link WikiTextParser}, we do not need to take of links or identifiers in quotes
   * anymore. Please do not use this function anymore.
   *
   * @param in ...
   * @param allIdentifiers ...
   * @return ...
   */
  @Deprecated
  protected static List<Word> concatenateLinks(List<Word> in, Set<String> allIdentifiers) {
    com.alexeygrigorev.rseq.Pattern<Word> linksPattern = com.alexeygrigorev.rseq.Pattern.create(
            pos(PosTag.QUOTE),
            anyWord().oneOrMore().captureAs("link"),
            pos(PosTag.UNQUOTE)
    );

    return linksPattern.replaceToOne(in, new TransformerToElement<Word>() {
      @Override
      public Word transform(Match<Word> match) {
        List<Word> words = match.getCapturedGroup("link");
        if (words.size() == 1 && allIdentifiers.contains("\\mathit{" + words.get(0).getWord() + "}")) {
          return new Word(joinWords(words), PosTag.IDENTIFIER);
        } else {
          return new Word(joinWords(words), PosTag.LINK);
        }
      }
    });
  }

  /**
   * Concatenates phrases to noun phrases
   * @param result
   * @return
   */
  protected static List<Word> concatenatePhrases(List<Word> result) {
    result = concatenateQuotes(result);
    result = concatenateSuccessiveNounsPossessiveEndingNounsSequence(result);
    result = concatenateSuccessiveAdjectiveNounsSequence(result);
    result = concatenateNounsPrepositionDeterminerAdjectiveNouns(result);
    return result;
  }

  protected static List<Word> concatenateQuotes(List<Word> in) {
    com.alexeygrigorev.rseq.Pattern<Word> pattern = com.alexeygrigorev.rseq.Pattern.create(
            txt(PosTag.QUOTE),
            anyWord().oneOrMore().captureAs("quoted"),
            txt(PosTag.UNQUOTE)
    );
    return pattern.replaceToOne(in, new TransformerToElement<Word>() {
      @Override
      public Word transform(Match<Word> match) {
        List<Word> matchSequence = match.getCapturedGroup("quoted");
        return linkSaveWord(matchSequence, matchSequence.get(matchSequence.size()-1).getPosTag());
      }
    });
  }

  protected static List<Word> concatenateSuccessiveNounsPossessiveEndingNounsSequence(List<Word> in) {
    com.alexeygrigorev.rseq.Pattern<Word> pattern = com.alexeygrigorev.rseq.Pattern.create(
            pos(PosTag.DETERMINER).optional(),
            pos(PosTag.ANY_ADJECTIVE_REGEX).zeroOrMore(),
            pos(PosTag.ANY_NOUN_REGEX + "|" + PosTag.FOREIGN_WORD).oneOrMore(),
            pos(PosTag.POSSESSIVE_ENDING),
            pos(PosTag.ANY_ADJECTIVE_REGEX).zeroOrMore(),
            pos(PosTag.ANY_NOUN_REGEX + "|" + PosTag.FOREIGN_WORD).oneOrMore()
    );

    return pattern.replaceToOne(in, new TransformerToElement<Word>() {
      @Override
      public Word transform(Match<Word> match) {
        List<Word> matchedSequence = match.getMatchedSubsequence();
        return linkSaveWord(matchedSequence, PosTag.NOUN_PHRASE);
      }
    });
  }

  protected static List<Word> concatenateSuccessiveAdjectiveNounsSequence(List<Word> in) {
    com.alexeygrigorev.rseq.Pattern<Word> pattern = com.alexeygrigorev.rseq.Pattern.create(
            pos(PosTag.DETERMINER).optional(),
            pos(PosTag.ANY_ADJECTIVE_REGEX).zeroOrMore(),
            pos(PosTag.ANY_NOUN_REGEX + "|" + PosTag.FOREIGN_WORD).oneOrMore()
    );

    return pattern.replaceToOne(in, new TransformerToElement<Word>() {
      @Override
      public Word transform(Match<Word> match) {
        List<Word> matchedSequence = match.getMatchedSubsequence();
        return linkSaveWord(matchedSequence, PosTag.NOUN_PHRASE);
      }
    });
  }

  protected static List<Word> concatenateNounsPrepositionDeterminerAdjectiveNouns(List<Word> in) {
    com.alexeygrigorev.rseq.Pattern<Word> pattern = com.alexeygrigorev.rseq.Pattern.create(
            pos(PosTag.DETERMINER).optional(),
            pos(PosTag.ANY_ADJECTIVE_REGEX).zeroOrMore(),
            pos(PosTag.ANY_NOUN_REGEX + "|" + PosTag.FOREIGN_WORD).oneOrMore(),
            pos(PosTag.PREPOSITION).optional(),
            pos(PosTag.DETERMINER).optional(),
            pos(PosTag.ANY_ADJECTIVE_REGEX).zeroOrMore(),
            pos(PosTag.ANY_NOUN_REGEX + "|" + PosTag.FOREIGN_WORD).zeroOrMore()
    );
    return pattern.replaceToOne(in, new TransformerToElement<Word>() {
      @Override
      public Word transform(Match<Word> match) {
        List<Word> matchedSequence = match.getMatchedSubsequence();
        return linkSaveWord(matchedSequence, matchedSequence.get(matchedSequence.size()-1).getPosTag());
      }
    });
  }

  protected static List<Word> concatenateSuccessiveNounsToNounSequence(List<Word> in) {
    XMatcher<Word> noun = posIn(PosTag.FOREIGN_WORD, PosTag.NOUN, PosTag.NOUN_PHRASE, PosTag.NOUN_PROPER, PosTag.NOUN_PLURAL, PosTag.NOUN_PROPER_PLURAL);
    return concatenateSuccessiveTags(in, noun, PosTag.NOUN_PHRASE);
  }

  protected static List<Word> concatenateSuccessiveAdjectives(List<Word> in) {
    XMatcher<Word> adjs = posIn(PosTag.ADJECTIVE, PosTag.ADJECTIVE_COMPARATIVE, PosTag.ADJECTIVE_SUPERLATIVE);
    return concatenateSuccessiveTags(in, adjs, PosTag.ADJECTIVE_SEQUENCE);
  }

  private static Word linkSaveWord(List<Word> sequence, String posTag) {
    Word link = sequence.stream().filter( w -> w.getPosTag().equals(PosTag.LINK) ).findFirst().orElse(null);
    if ( link != null )
      return new Word(sequence.get(0).getPosition(), link.getWord(), PosTag.LINK);
    else return new Word(sequence.get(0).getPosition(), joinWords(sequence), posTag);
  }

  private static List<Word> concatenateSuccessiveTags(List<Word> in, XMatcher<Word> matcher, String newTag) {
    com.alexeygrigorev.rseq.Pattern<Word> nounPattern = com.alexeygrigorev.rseq.Pattern.create(matcher.oneOrMore());
    return nounPattern.replaceToOne(in, new TransformerToElement<Word>() {
      @Override
      public Word transform(Match<Word> match) {
        List<Word> words = match.getMatchedSubsequence();
        if (words.size() == 1) {
          return words.get(0);
        }

        return new Word(words.get(0).getPosition(), joinWords(words), newTag);
      }
    });
  }

  protected static List<Word> concatenateTwoSuccessiveRegexTags(List<Word> in, String tag1, String tag2, String outputTag) {
    com.alexeygrigorev.rseq.Pattern<Word> pattern = com.alexeygrigorev.rseq.Pattern.create(pos(tag1), pos(tag2));
    return pattern.replaceToOne(in, m -> {
      List<Word> matchedSequence = m.getMatchedSubsequence();
      return new Word(matchedSequence.get(0).getPosition(), joinWords(matchedSequence), outputTag);
    });
  }

  private static String joinWords(List<Word> list) {
    LinkedList<String> toJoin = new LinkedList<>();
    list.forEach(w -> toJoin.add(w.getWord()));
    toJoin.removeLast();
    toJoin.add(list.get(list.size()-1).getLemma());
    return StringUtils.join(toJoin, " ");
  }

  private static XMatcher<Word> txt(String txt) {
    return BeanMatchers.regex(Word.class, "word", txt);
  }

  private static XMatcher<Word> pos(String tag) {
    XMatcher<Word> matcher = BeanMatchers.regex(Word.class, "originalPosTag", tag);
    return Matchers.and(matcher, Matchers.not( txt("-.*-|"+PlaceholderLib.PATTERN_STRING) ));
  }

  private static XMatcher<Word> posIn(String... tags) {
    XMatcher<Word> matcher = BeanMatchers.in(Word.class, "originalPosTag", ImmutableSet.copyOf(tags));
    return Matchers.and(matcher, Matchers.not( txt("-.*-|"+PlaceholderLib.PATTERN_STRING) ));
  }

  private static XMatcher<Word> anyWord() {
    return Matchers.anything();
  }
}
