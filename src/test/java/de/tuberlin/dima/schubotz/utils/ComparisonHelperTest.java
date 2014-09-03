package de.tuberlin.dima.schubotz.utils;

import de.tuberlin.dima.schubotz.fse.utils.ComparisonHelper;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Scanner;

import static junit.framework.TestCase.assertEquals;


/**
 * Test class for XML comparison methods.
 * This should test all methods of comparison,
 * not just the one being used.
 */
@RunWith(Parameterized.class)
@Ignore
public class ComparisonHelperTest {
    private String prefix;
    private double expectedScore;

    /**
     * Params in form of prefix, expected result.
     * Prefixes are the de.tuberlin.dima.schubotz.de.tuberlin.dima.schubotz.fse.common prefix of the test resource
     * @return array of parameters
     */
    @Parameterized.Parameters
	public static Collection<Object[]> inputNumDocs() {
		return Arrays.asList(new Object[][]{
                {"de/tuberlin/dima/schubotz/utils/qvar.MML.Identical.xml", 24.0}
        });
	}

    public ComparisonHelperTest(String prefix, double score) {
        this.prefix = prefix;
        this.expectedScore = score;
    }

    //TODO merge this with TestUtils' method and WikiAbstractSubprocess' method
    private static String getFileAsString(String filename) throws IOException {
        InputStream resource = ComparisonHelperTest.class.getClassLoader().getResourceAsStream(filename);
        if (resource == null) {
            //Try again with absolute path
            //Throws FileNotFound exception
            resource = new BufferedInputStream(new FileInputStream(filename));
        }
        try {
            //Stupid scanner tricks to read the entire file as one token
            final Scanner s = new Scanner(resource).useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }finally {
            resource.close();
        }
    }

    @Test
    public void testComparison() throws IOException {
        final String in = getFileAsString(prefix + ".xml");
        final String compare = getFileAsString(prefix + ".compare.xml");
        //hack while coding better comparison
        final int numMatches = ComparisonHelper.calculateMMLScore(in, compare);
        assertEquals("Number of matches does not match expected", expectedScore, (double) numMatches);
    }
}
