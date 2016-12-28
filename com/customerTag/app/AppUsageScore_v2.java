package com.customerTag.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.orc.OrcNewInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcNewOutputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.ql.io.orc.OrcStruct;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.DefaultStringifier;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

/*
 * �����û�������app����ʹ�������֣�ʹ��������Խ�ã�����Խ��
 * �����ڵ�һ������Ͻ��û�����ʹ��Сʱ���������ڣ�
 * ��������ͳ�ƺõĸ��û�ÿ��appʹ��Сʱ��˥�����ӣ�����ÿ���û�ʹ����Ϊ����(0.1-0.6),�����ۻ�����֮��ó�����
 * */
public class AppUsageScore_v2 {

	private static final HashMap<String, Float> Scores = new HashMap<String, Float>();
	
	private static class ExtractorMapper extends
	Mapper<NullWritable, Writable, Text, Text> {
	private static final String TAB = "|";
	private static final String SCHEMA = "struct<msisdn:string,appid:string,month:string,hoursperday:string,daysthismonth:string,hoursthismonth:string,timesthismonth:string,reportmonth:string>";
	private static HashMap<String,String> hm = new HashMap<String,String>();
	@Override
	protected void setup(Context context) throws IOException,
			InterruptedException {
		Configuration conf = context.getConfiguration();
		FileSystem fs = FileSystem.get(conf);
		String thisMonth = DefaultStringifier.load(conf, "month", Text.class).toString();
		
		Date currentMonth = new Date();
		try {
			currentMonth = new SimpleDateFormat("yyyyMM").parse(thisMonth);
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}//������ʼ����
		Calendar dd = Calendar.getInstance(Locale.CHINA);//��������ʵ��
		dd.setTime(currentMonth);//����������ʼʱ��
		dd.add(Calendar.MONTH, -5);
		while(dd.getTime().before(currentMonth) || dd.getTime().equals(currentMonth)){//�ж��Ƿ񵽽�������
			SimpleDateFormat sdf = new SimpleDateFormat("yyyyMM");
			String iterator_month = sdf.format(dd.getTime());
			//��ȡ�ļ����������ļ�
			String ini="/user/hive/warehouse/label.db/app_usage_month/reportmonth=" + iterator_month;
			FileStatus[] status = fs.listStatus(new Path(ini));
			if(status.length>0){
				for(FileStatus file:status){
					FSDataInputStream in = fs.open(file.getPath());
					BufferedReader bf = new BufferedReader(new InputStreamReader(in));
					String str = null;
					while ((str = bf.readLine()) != null) {
						String[] rules = str.split("\001");
						StringBuffer key = new StringBuffer();
						key.append(rules[0]).append(rules[1]).append(iterator_month);
						hm.put(key.toString(), rules[2]);
					}
					if (bf != null) {
						bf.close();
					}
				}
			}

			dd.add(Calendar.MONTH, 1);//���е�ǰ�����·ݼ�1
		}
		
		
	}
	
	@Override
	protected void map(
			NullWritable key, 
			Writable value,
			Mapper<NullWritable, Writable, Text, Text>.Context context)
			throws IOException, InterruptedException {
			OrcStruct struct = (OrcStruct)value;
			TypeInfo typeInfo =
		            TypeInfoUtils.getTypeInfoFromTypeString(SCHEMA);
		    
		    StructObjectInspector inspector = (StructObjectInspector)
		            OrcStruct.createObjectInspector(typeInfo);
		    
		   try{
		    	String msisdn = inspector.getStructFieldData(struct, inspector.getStructFieldRef("msisdn")).toString().trim();
		        String appid = inspector.getStructFieldData(struct, inspector.getStructFieldRef("appid")).toString().trim();
		        String hoursthismonth = inspector.getStructFieldData(struct, inspector.getStructFieldRef("hoursthismonth")).toString().trim();
		        String month = inspector.getStructFieldData(struct, inspector.getStructFieldRef("month")).toString().trim();
				//����Ӧ��ƥ����д��context;
		        if (msisdn != null && !"".equals(msisdn) && appid != null && !"".equals(appid)){
		        	String appScore = "";
		        	StringBuffer sb = new StringBuffer();
		        	sb.append(appid).append(hoursthismonth).append(month);
		        	if((appScore = hm.get(sb.toString().trim())) != null){
		        		StringBuffer outputKey = new StringBuffer();
		    			outputKey.append(msisdn);
		    			outputKey.append(TAB);
						outputKey.append(appid);
						StringBuffer outputValue = new StringBuffer();
						outputValue.append(month);
						outputValue.append(TAB);
						outputValue.append(appScore);
						context.write(new Text(outputKey.toString()),new Text(outputValue.toString()));
		        	}										
		        }
		    }catch(Exception e){};
		}
	}
	
	private static class ExtractorReducer extends
		Reducer<Text, Text, NullWritable, Writable > {
		
		protected void setup(Context context) throws IOException,
		InterruptedException {
			
			Configuration conf = context.getConfiguration();
			String thisMonth = DefaultStringifier.load(conf, "month", Text.class).toString();
			Date currentMonth = new Date();
			try {
				currentMonth = new SimpleDateFormat("yyyyMM").parse(thisMonth);
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}//������ʼ����
			Calendar dd = Calendar.getInstance(Locale.CHINA);//��������ʵ��
			dd.setTime(currentMonth);//����������ʼʱ��
			dd.add(Calendar.MONTH, -5);
			float score = 0.1f;	//����
			while(dd.getTime().before(currentMonth)){//�ж��Ƿ񵽽�������
				SimpleDateFormat sdf = new SimpleDateFormat("yyyyMM");
				String iterator_month = sdf.format(dd.getTime());
				Scores.put(iterator_month, score);
				dd.add(Calendar.MONTH, 1);//���е�ǰ�����·ݼ�1
				score = score + 0.1f;
			}
			Scores.put(thisMonth, score);
		}
		@Override
		protected void reduce(Text key, Iterable<Text> values, Context context)
				throws IOException, InterruptedException {
			
			float scores = 0.0f;
			
			List<String> monthlist = new LinkedList<String>();
			HashMap<String,Float> appScore = new HashMap<String,Float>();
			String[] keys = key.toString().split("\\|"); 
			//ͳ��appʹ���·�
			for(Text val:values){
				String[] vals = val.toString().trim().split("\\|");
				String month = vals[0];
				if(!monthlist.contains(month)){
					monthlist.add(month);
					appScore.put(month, Float.parseFloat(vals[1]));
				}
			}
			Collections.sort(monthlist);
			
			//ѭ��ͳ��ʹ���·ݣ��Ҵ��;
			float[] score_app = new float[monthlist.size()];
			for(int i = 0;i < monthlist.size();i++){
				String month = monthlist.get(i);
				score_app[i] = Scores.get(month)*appScore.get(month);
			}
			for(int i = 0;i < monthlist.size();i++){
				for(int j = 0;j <= i;j++){
					scores = scores + score_app[j];
				}
			}
			
			scores = scores/5.6f;
			DecimalFormat df = new DecimalFormat("0.0000000000");
			
			//дorc file��ʽ;
			String[] result = new String[3];
			System.arraycopy(keys, 0, result, 0, keys.length);
			result[2] = df.format(scores);
			OrcSerde orcSerde = new OrcSerde();
			Writable row;
			StructObjectInspector inspector = 
					(StructObjectInspector) ObjectInspectorFactory
					.getReflectionObjectInspector(AppUsageScoreRow.class,
							ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
			row = orcSerde.serialize(new AppUsageScoreRow(result), inspector);
			
			context.write(NullWritable.get(), row);
		}
	}
	
	/**
	* @param args
	* @throws URISyntaxException
	* @throws IOException
	* @throws ClassNotFoundException
	* @throws InterruptedException
	* @throws ParseException 
	*/
	public static void main(String[] args) throws IOException,
		URISyntaxException, InterruptedException, ClassNotFoundException, ParseException {
	
		String inputPath = "";
		String baseInputPath = "";
		if(args[0].endsWith("/")){
			baseInputPath = args[0]+"reportmonth=";
		}else{
			baseInputPath = args[0]+"/reportmonth=";
		}
		String month = args[1];
		String outputPath = "";
		if(args[2].endsWith("/")){
			outputPath = args[2];
		}else{
			outputPath = args[2]+"/";
		}
		outputPath = outputPath + "reportmonth=" + month;
		
		Configuration conf = new Configuration();
		DefaultStringifier.store(conf,new Text(month),"month");
		conf.set("mapreduce.job.queuename", "background");
		Job job = Job.getInstance(conf, "App Usage Frequency");
		job.setJarByClass(AppUsageScore_v2.class);
		job.setNumReduceTasks(240);
		job.setInputFormatClass(OrcNewInputFormat.class);
		job.setOutputFormatClass(OrcNewOutputFormat.class);
		FileSystem fs = FileSystem.get(conf);
		//�����������ڼ�������·��;
		Date currentMonth = new SimpleDateFormat("yyyyMM").parse(month);//������ʼ����
		Calendar dd = Calendar.getInstance(Locale.CHINA);//��������ʵ��
		dd.setTime(currentMonth);//����������ʼʱ��
		dd.add(Calendar.MONTH, -5);
		while(dd.getTime().before(currentMonth)){//�ж��Ƿ񵽽�������
			SimpleDateFormat sdf = new SimpleDateFormat("yyyyMM");
			String iterator_month = sdf.format(dd.getTime());
			//��������ļ����Ƿ���ڣ�
			if (fs.exists(new Path(baseInputPath + iterator_month + "/"))) {
				inputPath = inputPath + baseInputPath + iterator_month + "/,";
			}
			dd.add(Calendar.MONTH, 1);//���е�ǰ�����·ݼ�1
		}
		if (fs.exists(new Path(baseInputPath + month + "/"))) {
			inputPath = inputPath + baseInputPath + month + "/";
		}else{
			inputPath = inputPath.substring(0, inputPath.length()-1);
		}
		FileInputFormat.addInputPaths(job, inputPath);
		
		if (fs.exists(new Path(outputPath))) {
			fs.delete(new Path(outputPath), true);
			}
		FileOutputFormat.setOutputPath(job, new Path(outputPath));
		job.setMapOutputKeyClass(Text.class);
		job.setMapOutputValueClass(Text.class);
		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Writable.class);
		job.setMapperClass(ExtractorMapper.class);
		job.setReducerClass(ExtractorReducer.class);
		
		System.exit(job.waitForCompletion(true) ? 0 : 1);
		}

}
