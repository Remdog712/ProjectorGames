import unittest
import numpy as np
from app.tracking import homography, detect

class TrackingTests(unittest.TestCase):
    def setUp(self):
        self.matrix = homography([[0,0],[99,0],[99,99],[0,99]])
        self.bg = np.full((100,100),1000,dtype=np.uint16)

    def test_maps_contact(self):
        depth = self.bg.copy()
        depth[40:60,40:60] = 950
        hits = detect(depth,self.bg,self.matrix)
        self.assertEqual(len(hits),1)
        self.assertAlmostEqual(hits[0]['x'],.5,places=2)
        self.assertAlmostEqual(hits[0]['y'],.5,places=2)

    def test_invalid_and_outside_band(self):
        for value in [0,800,995,1100]:
            depth=self.bg.copy()
            depth[40:60,40:60]=value
            self.assertEqual(detect(depth,self.bg,self.matrix),[])

    def test_uncalibrated(self):
        self.assertEqual(detect(self.bg,None,self.matrix),[])
        self.assertEqual(detect(self.bg,self.bg,None),[])

    def test_bad_corners(self):
        for points in [[[0,0]]*4,[[0,0],[99,99],[99,0],[0,99]],[[0,0],[1,0],[1,1],[0,1]]]:
            with self.assertRaises(ValueError): homography(points)

if __name__=='__main__': unittest.main()
