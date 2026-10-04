# Local dish candidates

`mobilenet_v2_food101.onnx` is a real trained MobileNet V2 checkpoint, exported for CPU inference. It produces three possible **dish names** for the user to review. A model score is not calibrated confidence. The classifier never supplies ingredients, a serving mass, carbohydrate values or an insulin dose, and it must never select a nutrition record automatically.

Food-101 contains 101 broad dish categories, mostly restaurant food. It has no unknown-food class and does not cover many home recipes, mixed plates, local dishes or individual ingredients. An unfamiliar image can receive a high score for an incorrect category. The candidates are suggestions that require manual selection and separately verified nutrition and weighed portions.

## Provenance and licensing

- Public, nongated checkpoint: [AlexKoff88/mobilenet_v2_food101](https://huggingface.co/AlexKoff88/mobilenet_v2_food101/tree/7d05d3878ee7f2aad7ad159018a8e5fdb4953d41), revision `7d05d3878ee7f2aad7ad159018a8e5fdb4953d41`.
- Source checkpoint `pytorch_model.bin`: 19,121,039 bytes; SHA-256 `42cd5d9988f0830588b3be5107197c9b622c9b73426377975c07e8b24e346680`.
- Training code: [AlexKoff88/mobilenetv2_food101](https://github.com/AlexKoff88/mobilenetv2_food101/tree/a8d5c3c9003cfd561fa8af3f27bd9ce3f39cea67), revision `a8d5c3c9003cfd561fa8af3f27bd9ce3f39cea67`. Its code license and the Hugging Face model-card license are Apache-2.0; a copy is in `LICENSE-APACHE-2.0.txt`.
- Training checkpoint metadata: epoch 27, `best_acc1 = 76.3525`. The author's model card reports 76.3% top-1 on Food-101. This is an author-reported benchmark, not a promise of accuracy on users' meals.
- This repository modifies the format by exporting the verified checkpoint in evaluation mode with PyTorch 2.7.1 CPU and torchvision 0.22.1 CPU. The architecture, class count and weights are retained; no additional training is claimed.
- The upstream published ONNX file was rejected: its biases were zero and its outputs were effectively uniform on tested inputs. The bundled model is a fresh export of the separate trained checkpoint.
- `labels.txt` follows torchvision Food101's `sorted(metadata.keys())` order. Its 101 labels are also verified against the [ethz/food101 dataset metadata](https://huggingface.co/datasets/ethz/food101/tree/83488de741c1bd1ce27aa6a2b33e19c7bdf92ca9), revision `83488de741c1bd1ce27aa6a2b33e19c7bdf92ca9`. SHA-256 `eb6b396455a281445a698d8af22b2142be9a6eb9401520ee702746545f1c8820`.

No training or validation photographs are distributed in these assets. The original Food-101 image collection is sourced from Foodspotting; the dataset mirror reports its image license as unknown. This record distinguishes those photographs from the author's Apache-2.0 checkpoint declaration.

## Model contract

- ONNX opset 13; input `input`: float32 `[1, 3, 224, 224]`, RGB in NCHW order.
- Validation preprocessing: resize short edge to 256, center-crop 224, divide RGB by 255, normalize means `[0.485, 0.456, 0.406]` and standard deviations `[0.229, 0.224, 0.225]`.
- Android applies the equivalent centered crop geometry directly to a 224×224 bitmap with filtered scaling. Android and PIL interpolation can differ slightly; a native-device comparison remains useful.
- Output `output1`: float32 `[1, 101]` logits. The Android adapter computes a numerically stable softmax and returns the top three class IDs and model scores.
- Bundled model: 9,385,783 bytes; SHA-256 `ed386d53e69bb71f637dc9794429a5332e1c7dde4481ad177934ca0cc8aaa444`, checked before loading.
- Android runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` from Maven Central, minimum API 24, MIT license (copy in `LICENSE-ONNXRUNTIME-MIT.txt`). Its AAR SHA-256 is `82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827`. All PT_LOAD segments in the four supplied ABIs have `0x4000` alignment. Final APK ZIP alignment and runtime operation on a 16 KiB device still require their own verification.
- This runtime version has no telemetry initializer or network permissions in its manifest. The adapter explicitly disables runtime telemetry, uses the CPU provider and closes sessions, tensors and results after inference. User photo decoding is capped at 1024 pixels on the long edge; the tensor is always 224×224, with at most two inference threads.

## Reproducing the export

Use a development Python environment with `torch==2.7.1` and `torchvision==0.22.1` CPU wheels and the `onnx` package. Download the pinned checkpoint above, then run:

```sh
python reproduce-model.py /path/to/pytorch_model.bin /path/to/mobilenet_v2_food101.onnx
```

The script verifies the source checksum before using `torch.load(..., weights_only=True)`, loads all parameters strictly, switches to evaluation mode and exports the fixed-shape graph. It checks the output checksum and compares PyTorch and ONNX Runtime outputs when the `onnxruntime` Python package is installed.

`inference-smoke-report.json` records actual CPU inference with Python ONNX Runtime 1.23.2 on five distinct Food-101 validation examples, including the fixture hashes, expected category and top three outputs. All five had the expected category first, finite outputs and a softmax sum near one. Their outputs also matched Python Runtime 1.30.0 with a maximum score difference of zero. Random noise still generated dish names, demonstrating the absence of an unknown class. This small check verifies execution and label ordering; it does not estimate general accuracy or test the Android UI/device.
