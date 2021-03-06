import java.io.IOException;
import java.util.*;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;

public class Predict extends Configured implements Tool {
	
	//Usage
	static final String USAGE = "Predict -i <input_folder> -o <output_folder> -p <parameters_folder> [options]";
			
	//Keys to find HadoopPerceptron options in the configuration
	static final String K_PARAMETERS_FOLDER="HP.parameters.folder";
	static final String K_INPUT_FOLDER="HP.input.folder";
	static final String K_OUTPUT_FOLDER="HP.output.folder";
	static final String K_N_MAP="HP.number.map.tasks";
	static final String K_N_REDUCE="HP.number.reduce.tasks";

	static Options options=initOptions();
	private static Options initOptions(){
		Options options = new Options();

		OptionBuilder.hasArg(false);
		OptionBuilder.withDescription("Display usage.");
		options.addOption(OptionBuilder.create("help"));
		
		OptionBuilder.withArgName("input_folder");
		OptionBuilder.hasArg(true);
		OptionBuilder.withDescription("Folder in the hadoop dfs containing the text to be labelled.");
		OptionBuilder.isRequired(true);
		options.addOption(OptionBuilder.create("i"));

		OptionBuilder.withArgName("output_folder");
		OptionBuilder.hasArg(true);
		OptionBuilder.withDescription("Folder in the hadoop dfs where the labelled text is going to be saved.");
		OptionBuilder.isRequired(true);
		options.addOption(OptionBuilder.create("o"));

		OptionBuilder.withArgName("parameters_folder");
		OptionBuilder.hasArg(true);
		OptionBuilder.withDescription("Folder in the hadoop dfs containing the parameters of the model.");
		OptionBuilder.isRequired(true);
		options.addOption(OptionBuilder.create("p"));

		OptionBuilder.withArgName("integer");
		OptionBuilder.hasArg(true);
		OptionBuilder.withDescription("Set recommended number of map tasks.");
		OptionBuilder.withType(Integer.class);
		options.addOption(OptionBuilder.create("M"));

		OptionBuilder.withArgName("integer");
		OptionBuilder.hasArg(true);
		OptionBuilder.withDescription("Set recommended number of reduce tasks.");
		OptionBuilder.withType(Integer.class);
		options.addOption(OptionBuilder.create("R"));

		return options;
	}

	public static class Map extends MapReduceBase implements
	Mapper<LongWritable, Text, Text, Text> {

		private LinearModel perceptron = new LinearModel();
		JobConf conf = null;

		@Override
		public void configure(JobConf jc) {
			conf = jc;
			perceptron.readWeights(conf);
		}

		public void map(LongWritable key, Text value,
				OutputCollector<Text, Text> output, Reporter reporter)
						throws IOException {

			StringBuilder out = new StringBuilder();
			out.append("|||\t");

			Sentence sentence = new Sentence(value.toString());
			String predLabel = "";

			for (int i = 0; i < sentence.size(); i++) {

				predLabel = perceptron.predict(Features.getFeatures(sentence
						.getWord(i - 1), sentence.getWord(i), sentence
						.getWord(i + 1), predLabel));

				if(i!=0)out.append(" ");
				out.append(predLabel);
			}

			// <in sentence, sequence of labels>
			output.collect(value, new Text(out.toString()));
		}
	}

	public static class Reduce extends MapReduceBase implements
	Reducer<Text, Text, Text, Text> {
		public void reduce(Text key, Iterator<Text> values,
				OutputCollector<Text, Text> output, Reporter reporter)
						throws IOException {
			output.collect(key, values.next());
		}
	}

	public int run(String[] args) throws Exception {
		JobConf conf = new JobConf(getConf(), Predict.class);
		conf.setJobName("predict");

		conf.setOutputKeyClass(Text.class);
		conf.setOutputValueClass(Text.class);

		conf.setMapperClass(Map.class);
		conf.setCombinerClass(Reduce.class);
		conf.setReducerClass(Reduce.class);

		conf.setInputFormat(TextInputFormat.class);
		conf.setOutputFormat(TextOutputFormat.class);

		FileInputFormat.setInputPaths(conf, new Path(conf.get(K_INPUT_FOLDER)));
		FileOutputFormat.setOutputPath(conf, new Path(conf.get(K_OUTPUT_FOLDER)));

		int nMap=conf.getInt(K_N_MAP,-1);
		if (nMap>0){
			conf.setNumMapTasks(nMap);
		}
		int nRed=conf.getInt(K_N_REDUCE,-1);
		if (nRed>0){
			conf.setNumReduceTasks(nRed);
		}
		
		if(DistributedCacheUtils.loadParametersFolder(conf.get(K_PARAMETERS_FOLDER), conf)==1)return 1;

		JobClient.runJob(conf);

		return 0;
	}

	public static void main(String[] args) throws Exception {
		if(Arrays.asList(args).contains("-help")){
			new HelpFormatter().printHelp( USAGE, options );
			System.exit(0);
		}
		try{
			CommandLine cmd = new PosixParser().parse(options, args);

			Configuration conf= new Configuration();
			conf.set(K_INPUT_FOLDER, cmd.getOptionValue("i"));
			conf.set(K_OUTPUT_FOLDER, cmd.getOptionValue("o"));
			conf.set(K_PARAMETERS_FOLDER, cmd.getOptionValue("p"));
			if (cmd.hasOption( "M" )) conf.set(K_N_MAP,cmd.getOptionValue("M"));
			if (cmd.hasOption( "R" )) conf.set(K_N_REDUCE,cmd.getOptionValue("R"));

			int res = ToolRunner.run(conf, new Predict(), new String[0]);
			System.exit(res);
		}

		catch( ParseException e ) {
			new HelpFormatter().printHelp( USAGE, options );
			System.err.println("\n\nError while parsing command line:\n"+e.getMessage()+"\n");
		}
	}
}
