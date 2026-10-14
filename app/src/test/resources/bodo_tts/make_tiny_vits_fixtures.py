#!/usr/bin/env python3
"""
Generates the tiny ONNX fixtures used by OrtTtsBackendTest. They mimic the *I/O contract* of
MatiasLin/sherpa-onnx-vits-rasa-13 (exporter: export_onnx_vits_rasa_13.py) without any real model:

  inputs : x int64 [N,L], x_length int64 [N], noise_scale / length_scale / noise_scale_w float32
           (rank-0 scalars, as exported by `torch.tensor(float)`), sid int64 [N], emotion_id int64 [N]
  output : y float32 [N,1,L]            (one sample per input token)
  meta   : sample_rate=24000 add_blank=1 n_speakers=1024 num_emotions=32   (24000 = the real artifact's metadata)

y[i] = x[i]*1e-4*length_scale + sid*0.01 + emotion_id*0.02 + noise_scale*0.003
       + noise_scale_w*0.001 + x_length*1e-5
so a test can verify that every tensor reached the right input with the right dtype and value.

Run:  python3 make_tiny_vits_fixtures.py     (needs: pip install onnx onnxruntime numpy)
"""
import os
import numpy as np
import onnx
from onnx import TensorProto as TP, helper, numpy_helper

HERE = os.path.dirname(os.path.abspath(__file__))


def const(name, value):
    return numpy_helper.from_array(np.array(value, dtype=np.float32), name)


def build(path, scalar_shape, with_emotion=True, drop_sid=False, extra_input=False):
    n, l = "N", "L"
    inputs = [
        helper.make_tensor_value_info("x", TP.INT64, [n, l]),
        helper.make_tensor_value_info("x_length", TP.INT64, [n]),
        helper.make_tensor_value_info("noise_scale", TP.FLOAT, scalar_shape),
        helper.make_tensor_value_info("length_scale", TP.FLOAT, scalar_shape),
        helper.make_tensor_value_info("noise_scale_w", TP.FLOAT, scalar_shape),
    ]
    if not drop_sid:
        inputs.append(helper.make_tensor_value_info("sid", TP.INT64, [n]))
    if with_emotion:
        inputs.append(helper.make_tensor_value_info("emotion_id", TP.INT64, [n]))
    if extra_input:
        inputs.append(helper.make_tensor_value_info("style_vector", TP.FLOAT, [1, 4]))

    inits = [
        numpy_helper.from_array(np.array([1], dtype=np.int64), "axes1"),
        const("c_x", 1e-4), const("c_sid", 0.01), const("c_emo", 0.02),
        const("c_ns", 0.003), const("c_nsw", 0.001), const("c_xl", 1e-5),
    ]
    nodes = [
        helper.make_node("Cast", ["x"], ["xf"], to=TP.FLOAT),
        helper.make_node("Mul", ["xf", "length_scale"], ["x_ls"]),
        helper.make_node("Mul", ["x_ls", "c_x"], ["acc0"]),
    ]
    acc = "acc0"

    def add_term(src_int, const_name, tag):
        nonlocal acc
        nodes.append(helper.make_node("Cast", [src_int], [f"{tag}_f"], to=TP.FLOAT))
        nodes.append(helper.make_node("Unsqueeze", [f"{tag}_f", "axes1"], [f"{tag}_f1"]))  # [N,1]
        nodes.append(helper.make_node("Mul", [f"{tag}_f1", const_name], [f"{tag}_t"]))
        nodes.append(helper.make_node("Add", [acc, f"{tag}_t"], [f"{tag}_acc"]))
        acc = f"{tag}_acc"

    if not drop_sid:
        add_term("sid", "c_sid", "sid")
    if with_emotion:
        add_term("emotion_id", "c_emo", "emo")
    add_term("x_length", "c_xl", "xl")
    for scalar, cname, tag in (("noise_scale", "c_ns", "ns"), ("noise_scale_w", "c_nsw", "nsw")):
        nodes.append(helper.make_node("Mul", [scalar, cname], [f"{tag}_t"]))
        nodes.append(helper.make_node("Add", [acc, f"{tag}_t"], [f"{tag}_acc"]))
        acc = f"{tag}_acc"
    nodes.append(helper.make_node("Unsqueeze", [acc, "axes1"], ["y"]))  # [N,1,L]

    graph = helper.make_graph(
        nodes, "tiny_vits_contract", inputs,
        [helper.make_tensor_value_info("y", TP.FLOAT, [n, 1, l])], initializer=inits,
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    meta = {"model_type": "vits", "sample_rate": "24000", "add_blank": "1", "n_speakers": "1024"}
    if with_emotion:
        meta["num_emotions"] = "32"
    for k, v in meta.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = k, v
    onnx.checker.check_model(model)
    onnx.save(model, os.path.join(HERE, path))


if __name__ == "__main__":
    build("tiny_vits_scalar.onnx", scalar_shape=[])            # what the real exporter declares
    build("tiny_vits_rank1.onnx", scalar_shape=[1])            # stock sherpa-onnx style [1] scalars
    build("tiny_vits_no_emotion.onnx", scalar_shape=[], with_emotion=False)
    build("tiny_vits_missing_sid.onnx", scalar_shape=[], drop_sid=True)
    build("tiny_vits_extra_input.onnx", scalar_shape=[], extra_input=True)
    print("fixtures written to", HERE)
