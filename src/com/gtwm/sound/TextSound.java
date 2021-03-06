/*
 *   Copyright 2020
 * 
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.gtwm.sound;

import edu.mit.jwi.Dictionary;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.item.*;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.simple.Document;
import edu.stanford.nlp.simple.Sentence;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;
import org.jfugue.midi.MidiFileManager;
import org.jfugue.pattern.Pattern;
import org.jfugue.player.Player;
import org.jfugue.realtime.RealtimePlayer;
import org.jfugue.theory.Note;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.*;

/**
 * Transforms a stream of text into sound using the overtone series and synset types
 * ie. synset 1 = root, synset 2 = first over tone etc.
 *
 * @author effiam
 */
public class TextSound {

	// Starting settings

	// Instrument
	static String instrument;

	// How long to hold each note for
	static double noteLength; // /1 = whole note (semibreve). /0.25 = crotchet
	static double baseNoteLength;

	// How long to wait before playing the next note
	//static double noteGap = 0.0001; // 1 / 32d; // 1/32 = good default, 0 = no
	static double noteGap = 1 / 32d;

	// How long to pause when a rest (space) is encountered
	static double restLength = 1 / 16d; // 1/8 = good default

	// How long to pause when a line break is encountered
	static double restLengthLineBreak;

	// Lowest note that can be played
	static double baseFrequency; // 128 Hz = Octave below middle C
	static double frequency;

	// Octave range in which to place notes
	static double octaves;
	static double baseOctaves;

	// Tempo in beats per second
	static double tempo;
	static double baseTempo;

	// Instrument default
	static String baseInstrument;

	// Volume
	static double volume;
	static double baseVolume = 10200d;

	// Attack
	static int attack;
	static int baseAttack = 64;

	// Decay
	static int decay;
	static int baseDecay = 64;

	// Pitch Bend
	static long pitchBend = 8192;

	// Pan
	static int pan;
	static int basePan = 64;

	// Default note operation
	enum noteOperationType {LEXNAMEFREQ, STATICFREQ, MUTE}

	static noteOperationType defaultNoteOperation = noteOperationType.LEXNAMEFREQ;

	// Per key or word operation
	static boolean perChar;
	static boolean perWord;

	// Which letter ordering (defined above) to use, zero indexed
	static int ordering;
	static List<String> orderings = new ArrayList<String>();

	static Player player;
	static SinglingPlayer singlingPlayer;
	static Thread threadPlayer;

	static Pattern pattern;
	static double patternCurrentTime = 0;

	//static Set<String> passingWords = new HashSet<String>(Arrays.asList("THE","A","AND","OR","NOT","WITH","THIS","IN","INTO","IS","THAT","THEN","OF","BUT","BY","DID","TO","IT","ALL"));
	static Set<String> passingWords = new HashSet<String>();

	//static List<WordMap.Mapping> items;
	static List<TransformationManager.Instruction> instructions = new ArrayList<>();

	// Keeping track of how many categories a word falls under
	static int lexCount = 0;

	// Sentiment state
	static boolean sentimentState;

	// JWI object
	static IDictionary dict;

	// Keep parameters within reasonable ranges ie. BASE_FREQUENCY(16.0, 2048)
	enum Setting {
		NOTE_LENGTH(0.01, 8.0), ARPEGGIATE_GAP(0.001, 0.5), REST_LENGTH(0.01, 0.5), BASE_FREQUENCY(16.0, 20000), OCTAVES(
				1.0, 10.0), TEMPO(6, 600), LETTER_ORDERING(0.0, 3.0), VOLUME(1.0, 16383), ATTACK(0, 127);

		Setting(double min, double max) {
			if (min == 0) {
				// Don't allow absolute zero as a min otherwise will never
				// recover, i.e. it won't be able to be changed by multiplication
				this.min = 0.0001;
			} else {
				this.min = min;
			}
			this.max = max;
		}

		public double keepInRange(double value) {
			if (value < this.min) {
				this.directionRollingAverage = (this.directionRollingAverage - 1d) / 2d;
				if (this.directionRollingAverage < -0.8) {
					//System.out.println(this.toString() + " too low at " + value + ", swapping direction. RA = " + this.directionRollingAverage);
					this.direction = !this.direction;
				}
				double returnValue = this.min + (this.min - value);
				if (returnValue > this.min && returnValue < this.max) {
					return returnValue;
				} else {
					//System.out.println("" + this + " return value " + returnValue + " out of range, instead " + ((returnValue % (this.max - this.min)) + this.min));
					return (Math.abs(returnValue) % (this.max - this.min)) + this.min;
				}
			} else if (value > this.max) {
				this.directionRollingAverage = (this.directionRollingAverage + 1d) / 2d;
				if (this.directionRollingAverage > 0.8) {
					//System.out.println(this.toString() + " too high at " + value + ", swapping direction. RA = " + this.directionRollingAverage);
					this.direction = !this.direction;
				}
				double returnValue = this.max - (value - this.max);
				if (returnValue > this.min && returnValue < this.max) {
					return returnValue;
				} else {
					//System.out.println("" + this + " return value " + returnValue + " out of range, instead " + ((returnValue % (this.max - this.min)) + this.min));
					return (Math.abs(returnValue) % (this.max - this.min)) + this.min;
				}
			} else {
				//System.out.println("" + this + " now " + value);
				return value;
			}
		}

		public boolean getDirection() {
			return this.direction;
		}

		private double min;

		private double max;

		private boolean direction = true;

		private double directionRollingAverage = 0d;

	}

	public static void runStuff() throws Exception {

		// Reset initial settings
		resetSettings();

		// Each ordering gives a different character
		// Mayzner's
		orderings.add("ETAOINSRHLDCUMFPGWYBVKXJQZ");
		// standard order frequency used by typesetters (https://en.wikipedia.org/wiki/Letter_frequency)
		orderings.add("ETAONRISHDLFCMUGYPWBVKXJQZ");
		// letter frequency based upon Oxford dictionary (https://languages.oup.com/)
		orderings.add("EARIOTNSLCUDPMHGBFYWKVXZJQ");

		pattern = new Pattern();
		pattern.setVoice(0);
		pattern.setInstrument(instrument);
		pattern.setTempo((int) tempo);
		patternCurrentTime = 0;
		volume = 10200d;
	}

	public static void doStartPlayer(String input) {
		if (player == null) {
			player = new Player();
		} else if (player != null) {
			try {
				player.getManagedPlayer().finish();
			} catch (Exception e) {
			}
			player = new Player();
		}

		if (singlingPlayer == null) {
			singlingPlayer = new SinglingPlayer();
		} else if (singlingPlayer != null) {
			singlingPlayer.stop();
			singlingPlayer = new SinglingPlayer();
		}

		//player = new Player();
		//singlingPlayer = new SinglingPlayer();

		threadPlayer = new Thread(singlingPlayer);

		input += new String(" ");
		pattern = processString(input, pattern);

		singlingPlayer.setPattern(pattern, player, baseNoteLength);

		//System.out.println("Start player:" + threadPlayer.getId());
		threadPlayer.start();
	}

	public static void doPlay() {
		//pattern = processString(input, pattern);
		//Player player = new Player();
		player.play(pattern);
		player.getManagedPlayer().finish();
	}

	public static void doSaveAsMidi(String input, String output) throws Exception {
		pattern = processString(input, pattern);

		File file = new File(output);
		MidiFileManager midiFileManager = new MidiFileManager();
		midiFileManager.savePatternToMidi(pattern, file);
	}

	public static void doSaveAsWAV(String input, String output) throws Exception {
		pattern = processString(input, pattern);

		// Save to MIDI first
		File midiFile = new File(output + ".mid");
		MidiFileManager midiFileManager = new MidiFileManager();
		midiFileManager.savePatternToMidi(pattern, midiFile);

		// Convert MIDI to WAV
		AudioInputStream stream = AudioSystem.getAudioInputStream(midiFile);
		File file = new File(new File(output) + ".wav");
		AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file);

		// Delete MIDI file
		midiFile.delete();

		// Close stream
		stream.close();
	}

	public static void doPause() {
		try {
			if (player.getManagedPlayer().isPlaying()) {
				player.getManagedPlayer().pause();
				singlingPlayer.stop();
			} else if (player.getManagedPlayer().isPaused()) {
				player.getManagedPlayer().resume();
				singlingPlayer.resume();
			}
		} catch (Exception e) {
		}
	}

	/**
	 * Turn the input string into a sound string that can be played by jFugue
	 */
	private static Pattern processString(String input, Pattern pattern) {
		// Construct URL to WordNet Dictionary directory on the computer
		String wordNetDirectory = "WordNet-3.0";
		String path = wordNetDirectory + File.separator + "dict";
		URL url = null;
		try {
			url = new URL("file", null, path);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}

		// Construct the Dictionary object and open it
		dict = new Dictionary(url);
		try {
			dict.open();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Create CoreNLP document
		Document doc = new Document(input);

		// Count
		int wordCount;
		char posletter;

		// Iterate sentences in input text
		for (Sentence sent : doc.sentences()) {

			List<String> words = sent.words();

			// Iterate words
			for (String word : words) {
				//System.out.println(word);
				wordCount = words.indexOf(word);

				double theRestLength = restLength;

				if (perWord) {
					//System.out.println(sent.posTag(wordCount));
					posletter = sent.posTag(wordCount).charAt(0);
					//System.out.println(posletter);
					/*if ("JNRV".contains(String.valueOf(posletter))) {
						sonifyWord(items, sent.lemma(wordCount), posletter, pattern);
					} else if (java.util.regex.Pattern.matches("[\\p{Punct}\\p{IsPunctuation}]", word)) {
						sonifyWord(items, word, 'S', pattern);
					} else {
						sonifyWord(items, word, posletter, pattern);
					}*/

					if (java.util.regex.Pattern.matches("[\\p{Punct}\\p{IsPunctuation}]", word)) {
						sonifyWord(word, sent.lemma(wordCount), 'S', pattern);
					} else {
						//System.out.println("POS : " + posletter);
						sonifyWord(word, sent.lemma(wordCount), posletter, pattern);
					}
					//System.out.println("Sentiment Analysis (" + word + ") : " + analyse(word));
				}

				// Add rest between words
				pattern.add("R/" + String.format("%f", theRestLength) + " ");
				patternCurrentTime += theRestLength;
				patternCurrentTime = Math.round(patternCurrentTime * 100.0) / 100.0;
			}

			// An extra rest on newlines
			//pattern.add("R/" + String.format("%f", restLengthLineBreak) + " ");
			//patternCurrentTime += restLengthLineBreak;

			// Sentiment Analysis
			//System.out.println("Sentiment of sentence : " + sent.sentiment());
			pattern.add(" #(SENTENCE[" + sent.sentiment() + "])");
		}

		System.out.println(pattern.toString());
		return pattern;
	}

	public static void streamText(RealtimePlayer realtimePlayer, StringBuilder lastWord, boolean isWord, char ch, int charNum) {
		// Construct URL to WordNet Dictionary directory on the computer
		String wordNetDirectory = "WordNet-3.0";
		String path = wordNetDirectory + File.separator + "dict";
		URL url = null;
		try {
			url = new URL("file", null, path);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}

		// Construct the Dictionary object and open it
		dict = new Dictionary(url);
		try {
			dict.open();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Count
		int wordCount;
		char posletter;

		//StreamingPlayer streamingPlayer = new StreamingPlayer();
		try {
			//RealtimePlayer realtimePlayer = new RealtimePlayer();
			StringBuilder soundString = new StringBuilder();
			//double patternTimeStamp = 0;

			resetSettings();

			//soundString.append( (int) tempo + " I[" + instrument + "] " );
			Pattern pattern = new Pattern();
			pattern.setTempo((int) tempo);
			pattern.setInstrument(instrument);
			patternCurrentTime = 0;

			if (isWord) {
				//sonifyWord(lastWord, sent.lemma(wordCount), pattern,false);
			} else {
				sonifyCharacter(lastWord, pattern, charNum, ch);
			}

			pattern = pattern.add(soundString.toString());

			//soundString.append("R/" + String.format("%f", theRestLength) + " ");
			System.out.println("Staccato: " + pattern);

			//realtimePlayer.stream(soundString.toString());
			//realtimePlayer.play("T" + soundString);
			realtimePlayer.play(pattern);
			//realtimePlayer.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void sonifyWord(String originalWord, String wordLemma, char posLetter, Pattern pattern) {
		// Pattern transformed by sentiment values
		Pattern transformedPattern = new Pattern();

		Set<Integer> wordTypes = new HashSet<>();

		int posNumber = 0;

		// Nouns, verbs, adjectives, adverbs
		if ("JNRV".contains(String.valueOf(posLetter))) {
			switch (posLetter) {
				case 'J': posNumber = POS.NUM_ADJECTIVE; break;
				case 'N': posNumber = POS.NUM_NOUN; break;
				case 'R': posNumber = POS.NUM_ADVERB; break;
				case 'V': posNumber = POS.NUM_VERB; break;
			}

			//System.out.println(posNumber);
			IIndexWord idxWord = dict.getIndexWord(wordLemma, POS.getPartOfSpeech(posNumber));
			//System.out.println("idxWord : " + idxWord);

			// Store associated lexnames if found in WordNet
			if (idxWord != null) {
				// Loop to find all lexnames
				//int x = idxWord.getTagSenseCount();
				int x = idxWord.getWordIDs().size();
				//System.out.println("Number of wordIDs : " + idxWord.getWordIDs().size());

				for (int i = 0; i < x; i++) {
					IWordID wordID = idxWord.getWordIDs().get(i);
					IWord word = dict.getWord(wordID);

					//System.out.println("Id = " + wordID);
					//System.out.println(" Lemma = " + word.getLemma());
					//System.out.println(" Gloss = " + word.getSynset().getGloss());
					ISynset synset = word.getSynset();
					String LexFileName = synset.getLexicalFile().getName();
					//System.out.println("Lexical Name : "+ LexFileName + ":" + synset.getLexicalFile().getNumber());
					wordTypes.add(synset.getLexicalFile().getNumber());
				}
			}
			//System.out.println(wordTypes);

		// Other POStags
		} else {
			switch (posLetter) {
				// Determiner
				case 'D': wordTypes.add(46); break;
				// Modal
				case 'M': wordTypes.add(45); break;
				// Pronouns / Predeterminer / Possessive ending
				case 'P': wordTypes.add(51); break;
				// Preposition
				case 'I': wordTypes.add(48); break;
				// Symbols
				case 'S' : wordTypes.add(46); break;
			}
		}

		// First LGC of word will inherit the word as lyric item, sentiment analysis value, and any other NLP related data
		if (lexCount == 0) {
			pattern.add(" '(" + originalWord + ")");
			pattern.add(" #(SA[" + analyse(originalWord) + "], " + "LGC" + wordTypes + ", POS[" + posLetter + "]" +  ")");
		}

		// Iterate through list of lexnames for each word
		for (double thisValue : wordTypes) {
			//System.out.println(thisValue);
			// Reset to base settings
			resetSettings();
			pattern.add("I[" + instrument + "] ");
			pattern.add(":CE(935," + (int) volume + ")");
			pattern.add(":CE(10,64)");

			// Set voice
			if (wordTypes.size() > 1) {
				// Skip Voice channel 9 as that is for percussion instruments
				if (lexCount == 9) {
					lexCount++;
				}

				if (lexCount < 15) {
					pattern.add("V" + lexCount + " @" + patternCurrentTime);
				}
			}

			// Word value + 1 because it starts at 0 in the database
			double targetOctave = Math.ceil(((thisValue + 1) / 45d) * octaves); //26
			frequency = baseFrequency; // = convertToArr.toDoubleArr(item.getValue())[0]+1 * baseFrequency;

			switch (defaultNoteOperation) {
				case LEXNAMEFREQ:
					//System.out.println("freq: " + convertToArr.toDoubleArr(item.getValue())[0]);
					frequency = (thisValue + 1) * baseFrequency;
					break;
				case STATICFREQ:
					// If we want a default tone, leave freq as static
					break;
				case MUTE:
					// Mute tone
					//noteLength = 0;
					pattern.add(":CE(935,0)");
					//volume = 0;
					break;
			}

			// Go through the instructions queue
			for (TransformationManager.Instruction i : instructions) {

				transformedPattern.clear();

				// Make changes based on user instructions
				if (i.mod == TransformationManager.Instruction.Mods.WORDTYPE) {
						/*WordMap.Type[] wordtypes = convertToArr.toTypeArr(item.getType());
						for (WordMap.Type m : wordtypes) {
							if (m != null && m.toString().equals(i.modValue)) {
								applyMod(i, pattern);
							}
						}*/
					switch (posLetter) {
						case 'J':
							if (i.modValue.equals("adjective")) { applyMod(i, pattern); }
							break;
						case 'N':
							if (i.modValue.equals("noun")) { applyMod(i, pattern); }
							break;
						case 'R':
							if (i.modValue.equals("adverb")) { applyMod(i, pattern); }
							break;
						case 'V':
							if (i.modValue.equals("verb")) { applyMod(i, pattern); }
							break;
						case 'M':
							if (i.modValue.equals("modal")) { applyMod(i, pattern); }
							break;
						case 'S':
							if (i.modValue.equals("symbol")) { applyMod(i, pattern); }
							break;
						case 'D':
							if (i.modValue.equals("determiner")) { applyMod(i, pattern); }
							break;
						case 'I':
							if (i.modValue.equals("preposition")) { applyMod(i, pattern); }
							break;
						case 'P':
							if (i.modValue.equals("pronoun")) { applyMod(i, pattern); }
							break;
					}

				} else if (i.mod == TransformationManager.Instruction.Mods.WORDLENGTH) {
					switch (i.getModOperator()) {
						case EQUALTO:
							if (Double.parseDouble(i.getModValue()) == originalWord.length()) {
								applyMod(i, pattern);
							}
							break;
						case LARGERTHAN:
							if (Double.parseDouble(i.getModValue()) < originalWord.length()) {
								applyMod(i, pattern);
							}
							break;
						case LESSTHAN:
							if (Double.parseDouble(i.getModValue()) > originalWord.length()) {
								applyMod(i, pattern);
							}
							break;
					}

				} else if (i.mod == TransformationManager.Instruction.Mods.LGC) {
					//double[] lexnames = convertToArr.toDoubleArr(item.getValue() + 1);
					//for (double n : lexnames) {
					//	if (n == Double.parseDouble(i.modValue)) {
					//System.out.println("Equal: " + convertToArr.toDoubleArr(item.getValue())[0] + " | " + Double.parseDouble(i.modValue));
					//		applyMod(i, soundString);
					//	}
					//}
					if (thisValue == Double.parseDouble(i.modValue)) {
						//System.out.println("Equal: " + convertToArr.toDoubleArr(item.getValue())[0] + " | " + Double.parseDouble(i.modValue));
						applyMod(i, pattern);
					}

				} else if (i.mod == TransformationManager.Instruction.Mods.PUNCTUATION) {
					//String[] punctuations = convertToArr.toStringArr(item.getValue());

					//for (String n : punctuations) {
					if (wordLemma.equals(i.modValue)) {
						//System.out.println("Equal: " + convertToArr.toDoubleArr(item.getValue())[0] + " | " + Double.parseDouble(i.modValue));
						applyMod(i, pattern);
					}
					//}
				} else if (i.mod == TransformationManager.Instruction.Mods.SENTIMENT) {
					if (i.getSentimentType().equals(TransformationManager.Instruction.SentimentTypes.POSITIVESENTIMENT)) {
						//if (item.getSentimentPos() != null && item.getSentimentPos() != "NULL") {
						//	transformedPattern = applySentimentMod(i, thisValue, item.getSentimentPos(), pattern);
						//}
					} else if (i.getSentimentType().equals(TransformationManager.Instruction.SentimentTypes.NEGATIVESENTIMENT)) {
						//if (item.getSentimentNeg() != null && item.getSentimentNeg() != "NULL") {
						//	transformedPattern = applySentimentMod(i, thisValue, item.getSentimentNeg(), pattern);
						//}
					}
				}
			}

			//frequency = Math.round(frequency * 100.0) / 100.0;

			// Make chord based on sentiment analysis value
			String sentimentChord = "";
			double sumSentimentValue;
			//if (item.getSentimentPos() != null && item.getSentimentPos() != "NULL" && !item.getSentimentPos().equalsIgnoreCase("0")) {
			//	//System.out.println(makeMajorChord(Double.parseDouble(item.wordSentimentPos)));
			//	sentimentChord = makeMajorChord(Double.parseDouble(item.wordSentimentPos));
			//} else if (item.getSentimentNeg() != null && item.getSentimentNeg() != "NULL" && !item.getSentimentNeg().equalsIgnoreCase("0")) {
			//	//System.out.println(makeMinorChord(Double.parseDouble(item.wordSentimentNeg)));
			//	sentimentChord = makeMinorChord(Double.parseDouble(item.wordSentimentNeg));
			//}
				/*if (item.getSentimentPos() != null && item.getSentimentPos() != "NULL" && item.getSentimentNeg() != null && item.getSentimentNeg() != "NULL") {
					sumSentimentValue = Double.parseDouble(item.getSentimentPos()) + (Double.parseDouble(item.getSentimentNeg()) * -1);

					if (sumSentimentValue >= 0) {
						sentimentChord = makeMajorChord(sumSentimentValue);
					} else {
						sentimentChord = makeMinorChord(sumSentimentValue * -1);
					}
				}*/

			// Normalise to fit in the range
			double topFrequency = baseFrequency;
			for (int j = 0; j < targetOctave; j++) {
				topFrequency = topFrequency * 2;
			}
			while (frequency > topFrequency) {
				frequency = frequency / 2;
			}

			// Convert freq to MIDI music string using reference note and frequency A4 440hz
			int midiNumber = (int) Math.rint(12 * getLog(frequency / 440.0f, 2) + 69.0f);
			int baseMidiNumber = (int) Math.rint(12 * getLog(baseFrequency / 440.0f, 2) + 69.0f);

			// Find pitch using base midi note number
			pitchBend = Math.round(8192 + 4096 * 12 * getLog(frequency / (440.0f * Math.pow(2.0f, ((double) midiNumber - 69.0f) / 12.0f)), 2));
			//System.out.println("Pitch bend: " + pitchBend);
			//System.out.println("Frequency: " + frequency);
			//System.out.println("Midi Number: " + midiNumber);

			// JFugue's implementation which adds microtones as pitch bend events
			//pattern.add("m" + frequency + sentimentChord + "/" + noteLength + "a" + attack + "d" + decay + "");
			//pattern.add("m" + frequency + "/" + noteLength + "a" + attack + "d" + decay + "");
			//pattern.add("m512.3q");
			//pattern.add(":PitchWheel(5192) 72/0.25 :PitchWheel(8192)");

			//pattern.add(":PW(" + (int) pitchBend + ") " +  midiNumber + sentimentChord + "/" + noteLength + "a" + attack + "d" + decay + ":PW(8192)");
			//pattern.add(":PW(" + pitchBend + ") " +  midiNumber + sentimentChord + "/" + noteLength + "a" + attack + "d" + decay + " '" + lastWord);
			//pattern.add(":PW(" + pitchBend + ") " + midiNumber + sentimentChord + "/" + noteLength + "a" + attack + "d" + decay);

			//System.out.println("sentiment" + sentimentChord);
			// Midi message to send to player
			// If sentiment values are found, use the base (fundamental) frequency without pitchbend to create a chord
			// Otherwise, use the LGC as a variable to create a midi note

			// Sentiment Analysis

			/*if (sentimentState && sentimentChord != "") {
				pattern.add(baseMidiNumber + sentimentChord + "/" + noteLength + "a" + attack + "d" + decay);
			} else {
				pattern.add(":PW(" + pitchBend + ") " + midiNumber + "/" + noteLength + "a" + attack + "d" + decay);
			}*/

			Note note = new Note(":PW(" + pitchBend + ") " + midiNumber);
			//Chord chord = new Chord(":PW(" + pitchBend + ") " + midiNumber, new Intervals("1 3 5"));

			//pattern.add(note);
			//pattern.add("/" + noteLength);
			//pattern.add("a" + attack + "d" + decay);

			pattern.add(":PW(" + pitchBend + ") " + midiNumber + "/" + noteLength + "a" + attack + "d" + decay);
			//pattern.add(midiNumber + "maj/" + noteLength + "a" + attack + "d" + decay);

			//	}
			//}

			//System.out.println("Convert frequency: " + frequency + " to note: " + midiNumber);

			lexCount++;
		}

		// Note gap
		pattern.add("R/" + String.format("%f", noteGap) + " ");
		pattern.add("V0");

		patternCurrentTime = Math.round(patternCurrentTime * 100.0) / 100.0;
		patternCurrentTime += noteLength + noteGap;

		lexCount = 0;

	}

	public static void sonifyCharacter(StringBuilder lastWord, Pattern pattern, double charNum, char ch) {
		double targetOctave = Math.ceil((charNum / 26d) * octaves); //26
		frequency = baseFrequency; // = convertToArr.toDoubleArr(item.getValue())[0]+1 * baseFrequency;

		switch (defaultNoteOperation) {
			case LEXNAMEFREQ:
				//System.out.println("freq: " + convertToArr.toDoubleArr(item.getValue())[0]);
				frequency = charNum * baseFrequency;
				break;
			case STATICFREQ:
				// If we want a default tone, leave freq as static
				break;
			case MUTE:
				// Mute tone
				pattern.add(":CE(935,0)");
				break;
		}

		// Go through the instructions queue
		for (TransformationManager.Instruction i : instructions) {

			// The main logic part of the program
			// Make changes based on user instructions
			if (i.mod == TransformationManager.Instruction.Mods.CHARACTER) {
				//System.out.println("AEIOUaeiou".indexOf(ch));
				if (i.modValue.equals("vowels") && "AEIOUaeiou".indexOf(ch) != -1) {
					applyMod(i, pattern);
				} else if (i.modValue.equals("consonants") && "AEIOUaeiou".indexOf(ch) < 0) {
					applyMod(i, pattern);
				} else if (i.modValue.equals("uppercase") && Character.isUpperCase(ch)) {
					applyMod(i, pattern);
					System.out.println("uppercase");
				} else if (i.modValue.equals("lowercase") && Character.isLowerCase(ch)) {
					applyMod(i, pattern);
					System.out.println("lowercase");
				}

			} else if (i.mod == TransformationManager.Instruction.Mods.PUNCTUATION) {
				//String[] punctuations = convertToArr.toStringArr(item.getValue());

				//for (String n : punctuations) {
				if (ch == i.modValue.charAt(0)) {
					//System.out.println("Equal: " + convertToArr.toDoubleArr(item.getValue())[0] + " | " + Double.parseDouble(i.modValue));
					applyMod(i, pattern);
				}
				//}
			}
		}

		// Normalise to fit in the range
		double topFrequency = baseFrequency;
		for (int j = 0; j < targetOctave; j++) {
			topFrequency = topFrequency * 2;
		}
		while (frequency > topFrequency) {
			frequency = frequency / 2;
		}

		// Convert freq to MIDI music string using reference note and frequency A4 440hz
		int midiNumber = (int) Math.rint(12*getLog(frequency/440.0f, 2) + 69.0f);

		// Find pitch using base midi note number
		pitchBend = Math.round(8192+4096*12*getLog(frequency/(440.0f*Math.pow(2.0f, ((double)midiNumber-69.0f)/12.0f)), 2));

		// Set frequency of punctuations/symbols to frequency of lexname = 46
		if (java.util.regex.Pattern.matches("[\\p{Punct}\\p{IsPunctuation}]", String.valueOf(ch))) {
			targetOctave = Math.ceil((46/45d) * octaves); //26
			frequency = 46 * baseFrequency;
		}

		//pattern.add("m" + frequency + "/" + noteLength + "a" + attack + "d" + decay);
		//System.out.println("Convert frequency: " + frequency + ", note length: " + noteLength);
		pattern.add(":PW(" + pitchBend + ") " +  midiNumber + "/" + noteLength + "a" + attack + "d" + decay);

		//pattern.add(" '" + ch);

		// Testing
		System.out.println("Frequency for " + lastWord + "=" + charNum +
				" normalized to octave "
				+ octaves + ", top frequency " + topFrequency + ": " +
				frequency);
		//if (Character.isUpperCase(ch)) {
			//System.out.println("notelength: " + noteLength);
			//soundString.append("/" + String.format("%f", noteLength * 4)); // If it's an uppercase letter increase note length
			//System.out.println("soundString: " + soundString);
		//} else {
			//soundString.append("/" + String.format("%f", noteLength));
		//}

		//double theNoteGap = noteGap;
		//if (theNoteGap > 0.2) {
		//	theNoteGap = theNoteGap / lastWord.length();
		//} else if ((theNoteGap > 0.1) && passingWords.contains(lastWord.toString())) {
		//	theNoteGap = theNoteGap * 0.5;
		//}
		//soundString.append("+R/" + String.format("%f", noteGap) + " "); // Note + Resting gap
		pattern.add("+R/" + String.format("%f", noteGap) + " "); // Note + Resting gap

		// Reset to base settings
		resetSettings();
		//soundString.append("I[" + instrument + "] ");
		pattern.add("I[" + instrument + "] ");
		pattern.add("V0");
		pattern.add(":CE(935," + (int) volume + ")");
		pattern.add(":CE(10,64");
	}

	private static void applyMod(TransformationManager.Instruction i, Pattern pattern) {
		// Allow sound instructions to be played if notes are set to mute in default settings
		if (defaultNoteOperation == noteOperationType.MUTE) { pattern.add(":CE(935,10200)"); }

		switch (i.soundMod) {
			case TEMPO:
				Setting settingTempo = Setting.TEMPO;
				//if (lexCount <= 0) {
					if (i.changeMode == TransformationManager.Instruction.ChangeModes.SET) {
						tempo = Double.parseDouble(i.soundModValue);
						//soundString.append("T" + (int) tempo + " ");
					} else {
						tempo += Double.parseDouble(i.soundModValue);
						//baseTempo = tempo;
						//soundString.append("T" + (int) tempo + " ");
					}
					tempo = settingTempo.keepInRange(tempo);
					baseTempo = tempo;
					//soundString.append("T" + (int) tempo + " ");
					pattern.add("T" + (int)tempo);
				//}
				break;

			case NOTE_DURATION:
				Setting settingNoteDuration = Setting.NOTE_LENGTH;
				if (i.changeMode == TransformationManager.Instruction.ChangeModes.SET) {
					noteLength = settingNoteDuration.keepInRange(noteLength);
					noteLength = Double.parseDouble(i.soundModValue);
				} else {
					noteLength = settingNoteDuration.keepInRange(noteLength);
					noteLength += Double.parseDouble(i.soundModValue);
					baseNoteLength = noteLength;
				}
				//baseNoteLength = noteLength;
				break;

			case OCTAVE:
				Setting settingOctaves = Setting.OCTAVES;
				if (i.changeMode == TransformationManager.Instruction.ChangeModes.SET) {
					octaves = settingOctaves.keepInRange(octaves);
					octaves = Double.parseDouble(i.soundModValue);
				} else {
					octaves = settingOctaves.keepInRange(octaves);
					octaves += Double.parseDouble(i.soundModValue);
					baseOctaves = octaves;
				}
				//baseOctaves = octaves;
				break;

			case INSTRUMENT:
				//if (lexCount <= 0) {
				//	instrument = i.soundModValue;
				//	soundString.append("I[" + instrument + "] ");
				//}
				//pattern.setInstrument(i.soundModValue);
				pattern.add("I[" + i.soundModValue + "] ");
				break;

			case VOLUME:
				Setting settingVolume = Setting.VOLUME;
				//if (lexCount <= 0) {
					volume = Double.parseDouble(i.soundModValue);
					volume = settingVolume.keepInRange(volume);
					//soundString.append("X[Volume]=" + volume + " ");
					//pattern.add("X[Volume]=" + volume + " ");
					pattern.add(":CE(935," + (int) volume + ")");
				//}
				break;

			case PERCUSSION:
				//soundString.append("V9 [" + i.soundModValue + "]q V0 ");
				break;

			case MIDI_NOTE:
				//if (i.changeMode == Queue.Instruction.ChangeModes.SET) {
				//	frequency = Double.parseDouble(i.soundModValue);
				//} else {
				//	frequency += Double.parseDouble(i.soundModValue);
				//	baseFrequency = frequency;
				//}
				Setting settingsFrequency = Setting.BASE_FREQUENCY;
				//double midiNoteNumber = Double.parseDouble(i.soundModValue);

				//if (i.changeMode == TransformationManager.Instruction.ChangeModes.SET) {
					//frequency = Math.pow(2, (midiNoteNumber - 69) / 12) * 440;
					frequency = Note.getFrequencyForNote(i.soundModValue)*2;
					frequency = settingsFrequency.keepInRange(frequency);
				//} else {
					//double tempFreq = Math.pow(2, (midiNoteNumber - 69) / 12) * 440;
					//frequency += tempFreq;
				//	frequency = settingsFrequency.keepInRange(frequency);
				//	baseFrequency = frequency;
				//}
				//System.out.println("Change freq to: " + i.soundModValue);
				//System.out.println("Note: " + i.soundModValue + ", Freq: " + frequency);
				break;

			case ATTACK:
				Setting settingsAttack = Setting.ATTACK;
				attack = Integer.parseInt(i.soundModValue);
				attack = (int) settingsAttack.keepInRange(attack);
				break;

			case DECAY:
				Setting settingsDecay = Setting.ATTACK;
				decay = Integer.parseInt(i.soundModValue);
				decay = (int) settingsDecay.keepInRange(decay);
				break;

			case PITCHBEND:
				if (i.changeMode == TransformationManager.Instruction.ChangeModes.SET) {
					pitchBend = Long.parseLong(i.soundModValue);
				} else {
					pitchBend += Double.parseDouble(i.soundModValue);
				}
				break;

			case PAN:
				pan = Integer.parseInt(i.soundModValue);
				pattern.add(":CE(10," + pan + ")");
				break;
		}
	}

	private static Pattern applySentimentMod(TransformationManager.Instruction i, double wordValue, String sentimentValue, Pattern pattern) {
		Pattern sentimentPattern = new Pattern();
		Setting settingsFrequency = Setting.BASE_FREQUENCY;

		double tempNote;
		int midiNumber;
		double x;
		String thisNote;

		// Allow sound instructions to be played if notes are set to mute in default settings
		if (defaultNoteOperation == noteOperationType.MUTE) {
			pattern.add(":CE(935,10200)");
		}

		switch (i.soundMod) {
			case TEMPO:
				break;

			case MIDI_NOTE:
				double newWordValue;

				//if (i.sentimentType.equals(Queue.Instruction.SentimentTypes.POSITIVESENTIMENT)) {
					newWordValue = (Double.parseDouble(sentimentValue) * 100) * wordValue;
					frequency = Math.pow(2, (newWordValue - 69) / 12) * 440;
					frequency = settingsFrequency.keepInRange(frequency);
					//System.out.println("FREQUENCY= " + frequency);
					//System.out.println("SENTIMENTVALUE= " + sentimentValue);
				//} else if (i.sentimentType.equals(Queue.Instruction.SentimentTypes.NEGATIVESENTIMENT)) {

				//}
				break;

			case MAJOR:
				x = (Double.parseDouble(sentimentValue) * 100) * wordValue;
				frequency = Math.pow(2, (x - 69) / 12) * 440;
				frequency = settingsFrequency.keepInRange(frequency);

				// Convert freq to MIDI music string using reference note and frequency A4 440hz
				tempNote = 12 * getLog(frequency/440, 2) + 69;
				midiNumber = (int) Math.rint(tempNote);

				frequency = Math.round(frequency * 100.0) / 100.0;

				sentimentPattern.add(midiNumber + "maj");
				break;

			case MINOR:
				x = (Double.parseDouble(sentimentValue) * 100) * wordValue;
				frequency = Math.pow(2, (x - 69) / 12) * 440;
				frequency = settingsFrequency.keepInRange(frequency);

				// Convert freq to MIDI music string using reference note and frequency A4 440hz
				tempNote = 12 * getLog(frequency/440, 2) + 69;
				midiNumber = (int) Math.rint(tempNote);

				frequency = Math.round(frequency * 100.0) / 100.0;

				sentimentPattern.add(midiNumber + "min");
				break;
		}

		return sentimentPattern;
	}

	private static void resetSettings() {
		noteLength = baseNoteLength;
		frequency = baseFrequency;
		instrument = baseInstrument;
		octaves = baseOctaves;
		tempo = baseTempo;
		attack = baseAttack;
		decay = baseDecay;
		volume = baseVolume;
		pan = basePan;
		//ordering =
	}

	private static String makeMajorChord(double sentimentValue) {
		String[] majorChords = {"maj", "maj6", "maj7", "maj9", "add9", "maj6%9", "maj7%6", "maj13"};
		int chordNum = (int)Math.round(sentimentValue*10);
		while (chordNum > 7) {
			chordNum += -1;
		}
		return majorChords[chordNum];
	}

	private static String makeMinorChord(double sentimentValue) {
		String[] minorChords = {"min", "min6", "min7", "min9", "min11", "min7%11", "minadd9", "min6%9", "minmaj7", "minmaj9"};
		int chordNum = (int)Math.round(sentimentValue*10);
		while (chordNum > 9) {
			chordNum += -1;
		}
		return minorChords[chordNum];
	}

	private static class ProxyHighlightPainter implements Highlighter.HighlightPainter {

		private final Highlighter.HighlightPainter delegate;

		public ProxyHighlightPainter(Highlighter.HighlightPainter delegate) {
			this.delegate = delegate;
		}

		@Override
		public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
			int startSel = c.getSelectionStart();
			int endSel = c.getSelectionEnd();
			if (startSel == endSel || startSel >= p1 || endSel <= p0) {
				// no selection or no intersection: paint normal
				delegate.paint(g, p0, p1, bounds, c);
			} else if (startSel >= p0 && endSel >= p1) {
				delegate.paint(g, p0, startSel, bounds, c);
			} else if (startSel <= p0 && endSel <= p1) {
				delegate.paint(g, endSel, p1, bounds, c);
			} else if (startSel <= p0 && endSel <= p1) {
				delegate.paint(g, p0, startSel, bounds, c);
				delegate.paint(g, endSel, p1, bounds, c);
			} else {
				// just to be safe
				delegate.paint(g, p0, p1, bounds, c);
			}
		}
	}

	private static boolean containsIgnoreCase(Set<String> list, String soughtFor) {
		for (String current : list) {
			if (current.equalsIgnoreCase(soughtFor)) {
				return true;
			}
		}
		return false;
	}

	private static int analyse(String text) {
		Properties props = new Properties();
		props.setProperty("annotators", "tokenize, ssplit, pos, parse, sentiment");
		StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
		Annotation annotation = pipeline.process(text);
		for (CoreMap sentence : annotation.get(CoreAnnotations.SentencesAnnotation.class)) {
			Tree tree = sentence.get(SentimentCoreAnnotations.SentimentAnnotatedTree.class);
			return RNNCoreAnnotations.getPredictedClass(tree);
		}
		return 0;
	}

	private static double getLog(double x, double base) {
		return (Math.log(x) / Math.log(base));
	}
}



