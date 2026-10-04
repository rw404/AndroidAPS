"""Re-export the verified public Food-101 checkpoint; development use only."""

import argparse
import hashlib
from pathlib import Path

import onnx
import torch
import torchvision

CHECKPOINT_SHA256 = "42cd5d9988f0830588b3be5107197c9b622c9b73426377975c07e8b24e346680"
MODEL_SHA256 = "ed386d53e69bb71f637dc9794429a5332e1c7dde4481ad177934ca0cc8aaa444"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("checkpoint", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
assert hashlib.sha256(args.checkpoint.read_bytes()).hexdigest() == CHECKPOINT_SHA256
checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
weights = {key.removeprefix("module."): value for key, value in checkpoint["state_dict"].items()}
model = torchvision.models.mobilenet_v2(num_classes=101)
model.load_state_dict(weights, strict=True)
model.eval()
torch.set_num_threads(2)
with torch.no_grad():
    torch.onnx.export(
        model,
        torch.zeros(1, 3, 224, 224),
        args.output,
        opset_version=13,
        input_names=["input"],
        output_names=["output1"],
        dynamo=False,
    )
onnx.checker.check_model(onnx.load(args.output))
assert hashlib.sha256(args.output.read_bytes()).hexdigest() == MODEL_SHA256
try:
    import numpy as np
    import onnxruntime
except ImportError:
    print("Export and checksum verified; install onnxruntime for numerical equivalence check.")
else:
    values = np.random.default_rng(42).normal(size=(1, 3, 224, 224)).astype(np.float32)
    session = onnxruntime.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    onnx_output = session.run(["output1"], {"input": values})[0]
    with torch.no_grad():
        torch_output = model(torch.from_numpy(values)).numpy()
    assert np.isfinite(onnx_output).all()
    assert float(onnx_output.max() - onnx_output.min()) > 0.1
    np.testing.assert_allclose(onnx_output, torch_output, rtol=1e-4, atol=1e-5)
    print("Export, checksum and PyTorch/ONNX numerical equivalence verified.")
