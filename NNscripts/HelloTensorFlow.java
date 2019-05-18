import org.tensorflow.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.tensorflow.types.UInt8;

public class HelloTensorFlow {

  public static void main(String[] args) throws Exception {
	System.out.println("TensorFlow version: " + TensorFlow.version());
        //Get absolute path to src/main/resources/saved_model.pb
        Path modelPath = Paths.get(HelloTensorFlow.class.getResource("./model/tf_model.pb").toURI());
        byte[] graph = Files.readAllBytes(modelPath);
	
	final List<String> labels = loadLabels();

        try (Graph g = new Graph()) {
            g.importGraphDef(graph);

            //Just print needed operations for debug
            System.out.println(g.operation("resnet50_input").output(0));
            System.out.println(g.operation("dense/Softmax").output(0));

            //open session using imported graph
            try (Session session = new Session(g)) {
                float[][] probabilities = new float[1][103];
      for (String filename : args) {
        byte[] bytes = Files.readAllBytes(Paths.get(filename));
        try (Tensor<Float> input = constructAndExecuteGraphToNormalizeImage(bytes);
            Tensor<Float> output =
                session
                    .runner()
                    .feed("resnet50_input", input)
                    .fetch("dense/Softmax")
                    .run()
                    .get(0)
                    .expect(Float.class)) {
          if (probabilities == null) {
            probabilities = new float[(int) output.shape()[0]][(int) output.shape()[1]];
System.out.println(output.shape()[0]);
System.out.println(output.shape()[1]);
          }
          output.copyTo(probabilities);
          int label = argmax(probabilities[0]);
          System.out.printf(
              "%-30s --> %-15s (%.2f%% likely)\n",
              filename, labels.get(label), probabilities[0][label] * 100.0);
        }
}
            }
}
  }

private static ArrayList<String> loadLabels() throws IOException {
    ArrayList<String> labels = new ArrayList<String>();
    String line;
    final InputStream is = HelloTensorFlow.class.getClassLoader().getResourceAsStream("labels.txt");
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
      while ((line = reader.readLine()) != null) {
        labels.add(line);
      }
    }
    return labels;
}

private static int argmax(float[] probabilities) {
    int best = 0;
    for (int i = 1; i < probabilities.length; ++i) {
      if (probabilities[i] > probabilities[best]) {
        best = i;
      }
    }
    return best;
}

private static Tensor<Float> constructAndExecuteGraphToNormalizeImage(byte[] imageBytes) {
    try (Graph g = new Graph()) {
      GraphBuilder b = new GraphBuilder(g);
      // Some constants specific to the pre-trained model at:
      // https://storage.googleapis.com/download.tensorflow.org/models/inception5h.zip
      //
      // - The model was trained with images scaled to 224x224 pixels.
      // - The colors, represented as R, G, B in 1-byte each were converted to
      //   float using (value - Mean)/Scale.
      final int H = 224;
      final int W = 224;
      final float mean = 117f;
      final float scale = 1f;

      // Since the graph is being constructed once per execution here, we can use a constant for the
      // input image. If the graph were to be re-used for multiple input images, a placeholder would
      // have been more appropriate.
      final Output<String> input = b.constant("input", imageBytes);
      final Output<Float> output =
          b.div(
              b.sub(
                  b.resizeBilinear(
                      b.expandDims(
                          b.cast(b.decodeJpeg(input, 3), Float.class),
                          b.constant("make_batch", 0)),
                      b.constant("size", new int[] {H, W})),
                  b.constant("mean", mean)),
              b.constant("scale", scale));
      try (Session s = new Session(g)) {
        // Generally, there may be multiple output tensors, all of them must be closed to prevent resource leaks.
        return s.runner().fetch(output.op().name()).run().get(0).expect(Float.class);
      }
    }
}

static class GraphBuilder {
    GraphBuilder(Graph g) {
      this.g = g;
    }

    Output<Float> div(Output<Float> x, Output<Float> y) {
      return binaryOp("Div", x, y);
    }

    <T> Output<T> sub(Output<T> x, Output<T> y) {
      return binaryOp("Sub", x, y);
    }

    <T> Output<Float> resizeBilinear(Output<T> images, Output<Integer> size) {
      return binaryOp3("ResizeBilinear", images, size);
    }

    <T> Output<T> expandDims(Output<T> input, Output<Integer> dim) {
      return binaryOp3("ExpandDims", input, dim);
    }

    <T, U> Output<U> cast(Output<T> value, Class<U> type) {
      DataType dtype = DataType.fromClass(type);
      return g.opBuilder("Cast", "Cast")
          .addInput(value)
          .setAttr("DstT", dtype)
          .build()
          .<U>output(0);
    }

    Output<UInt8> decodeJpeg(Output<String> contents, long channels) {
      return g.opBuilder("DecodeJpeg", "DecodeJpeg")
          .addInput(contents)
          .setAttr("channels", channels)
          .build()
          .<UInt8>output(0);
    }

    <T> Output<T> constant(String name, Object value, Class<T> type) {
      try (Tensor<T> t = Tensor.<T>create(value, type)) {
        return g.opBuilder("Const", name)
            .setAttr("dtype", DataType.fromClass(type))
            .setAttr("value", t)
            .build()
            .<T>output(0);
      }
    }
    Output<String> constant(String name, byte[] value) {
      return this.constant(name, value, String.class);
    }

    Output<Integer> constant(String name, int value) {
      return this.constant(name, value, Integer.class);
    }

    Output<Integer> constant(String name, int[] value) {
      return this.constant(name, value, Integer.class);
    }

    Output<Float> constant(String name, float value) {
      return this.constant(name, value, Float.class);
    }

    private <T> Output<T> binaryOp(String type, Output<T> in1, Output<T> in2) {
      return g.opBuilder(type, type).addInput(in1).addInput(in2).build().<T>output(0);
    }

    private <T, U, V> Output<T> binaryOp3(String type, Output<U> in1, Output<V> in2) {
      return g.opBuilder(type, type).addInput(in1).addInput(in2).build().<T>output(0);
    }
    private Graph g;
}
}

